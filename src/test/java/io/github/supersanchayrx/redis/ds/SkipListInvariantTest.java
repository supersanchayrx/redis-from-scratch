package io.github.supersanchayrx.redis.ds;

import io.github.supersanchayrx.redis.store.Key;
import io.github.supersanchayrx.redis.store.ZSetValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Random ZADD/ZREM operations run against the sorted set and a reference model (a TreeMap keyed by
 * (score, member) plus a member-to-score map) at the same time. After every operation the whole structure
 * is checked, spans included.
 */
class SkipListInvariantTest {

    private record Entry(double score, Key member) {
    }

    private static final Comparator<Entry> ORDER =
            Comparator.comparingDouble(Entry::score).thenComparing(Entry::member);

    private static final int OPS = 10_000;

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 42, 1337, 20261022, 987654321})
    void randomOperationsKeepEveryInvariant(long seed) {
        SplittableRandom rnd = new SplittableRandom(seed);
        ZSetValue z = new ZSetValue(new SkipList(new SplittableRandom(seed * 31 + 7)));
        TreeMap<Entry, Boolean> model = new TreeMap<>(ORDER);
        Map<Key, Double> scores = new HashMap<>();
        List<Key> members = new ArrayList<>();
        int step = 0;
        try {
            for (step = 0; step < OPS; step++) {
                int op = rnd.nextInt(100);
                if (op < 45 || members.isEmpty()) {
                    Key m = randomMember(rnd);
                    double s = randomScore(rnd);
                    if (scores.containsKey(m)) {
                        model.remove(new Entry(scores.get(m), m));
                    } else {
                        members.add(m);
                    }
                    z.put(m, s);
                    scores.put(m, s);
                    model.put(new Entry(s, m), true);
                } else if (op < 70) {
                    // Re-score an existing member (exercises both the in-place path and delete + insert).
                    Key m = members.get(rnd.nextInt(members.size()));
                    double s = rnd.nextBoolean() ? scores.get(m) + rnd.nextDouble() * 0.001 : randomScore(rnd);
                    model.remove(new Entry(scores.get(m), m));
                    assertTrue(!z.put(m, s));
                    scores.put(m, s);
                    model.put(new Entry(s, m), true);
                } else if (op < 95) {
                    int i = rnd.nextInt(members.size());
                    Key m = members.get(i);
                    members.set(i, members.get(members.size() - 1));
                    members.remove(members.size() - 1);
                    model.remove(new Entry(scores.remove(m), m));
                    assertTrue(z.remove(m));
                } else {
                    Key missing = Key.of("missing-" + rnd.nextInt(1000));
                    if (!scores.containsKey(missing)) {
                        assertTrue(!z.remove(missing));
                    }
                }
                checkInvariants(z, new ArrayList<>(model.keySet()), rnd);
            }
        } catch (AssertionError e) {
            fail("seed " + seed + " failed at operation " + step + ": " + e.getMessage(), e);
        }
    }

    private static Key randomMember(SplittableRandom rnd) {
        int n = rnd.nextInt(3000);
        // Some members carry bytes >= 0x80, which must sort after ASCII (unsigned comparison).
        if (n % 7 == 0) {
            return Key.wrap(new byte[] {(byte) 0xc3, (byte) (n & 0xff), (byte) (n >> 8)});
        }
        return Key.of("m" + n);
    }

    private static double randomScore(SplittableRandom rnd) {
        // Few distinct scores, so ties (decided by member bytes) are common.
        return switch (rnd.nextInt(10)) {
            case 0 -> Double.NEGATIVE_INFINITY;
            case 1 -> Double.POSITIVE_INFINITY;
            case 2, 3 -> rnd.nextInt(5);
            default -> rnd.nextInt(200) - 100 + rnd.nextInt(4) * 0.25;
        };
    }

    static void checkInvariants(ZSetValue z, List<Entry> expected, SplittableRandom rnd) {
        SkipList sl = z.list;
        // 1. Length.
        assertEquals(expected.size(), sl.length(), "length");
        assertEquals(expected.size(), z.size(), "dict size");

        // 2. Level 0 gives exactly the model's order; collect nodes and their ranks.
        List<SkipList.Node> nodes = new ArrayList<>();
        Map<SkipList.Node, Integer> rankOf = new HashMap<>();
        rankOf.put(sl.header, 0);
        int r = 0;
        for (SkipList.Node n = sl.header.forward[0]; n != null; n = n.forward[0]) {
            Entry e = expected.get(r);
            assertEquals(e.score(), n.score, "score at rank " + (r + 1));
            assertEquals(e.member(), n.member, "member at rank " + (r + 1));
            assertSame(n, z.dict.get(n.member), "dict points at the node");
            nodes.add(n);
            rankOf.put(n, ++r);
        }
        assertEquals(expected.size(), r);

        // 3. Every span equals the level-0 distance to its forward node (or to the end, when it's null).
        assertTrue(sl.level >= 1 && sl.level <= SkipList.MAX_LEVEL);
        checkSpans(sl.header, 0, sl.level, rankOf, sl.length);
        for (SkipList.Node n : nodes) {
            assertTrue(n.forward.length <= sl.level, "node taller than the list's level");
            checkSpans(n, rankOf.get(n), n.forward.length, rankOf, sl.length);
        }
        // The list's level is the height of its tallest node.
        if (sl.level > 1) {
            assertTrue(sl.header.forward[sl.level - 1] != null, "top level is empty");
        }

        // 4. Backward pointers walk the list in exact reverse; the tail is the last node.
        if (nodes.isEmpty()) {
            assertNull(sl.tail);
        } else {
            assertSame(nodes.get(nodes.size() - 1), sl.tail);
            assertNull(nodes.get(0).backward);
            for (int i = 1; i < nodes.size(); i++) {
                assertSame(nodes.get(i - 1), nodes.get(i).backward, "backward at rank " + (i + 1));
            }
        }

        // 5. Rank and node-at-rank agree with the model for a few random members.
        for (int k = 0; k < 3 && !expected.isEmpty(); k++) {
            int i = rnd.nextInt(expected.size());
            Entry e = expected.get(i);
            assertEquals(i + 1, sl.rank(e.score(), e.member()), "rank of " + e.member());
            assertEquals(i, z.rank(e.member()));
            assertSame(nodes.get(i), sl.nodeAtRank(i + 1), "node at rank " + (i + 1));
        }
        assertNull(sl.nodeAtRank(0));
        assertNull(sl.nodeAtRank(sl.length + 1));
    }

    private static void checkSpans(SkipList.Node n, int rank, int levels, Map<SkipList.Node, Integer> rankOf, long len) {
        for (int i = 0; i < levels; i++) {
            SkipList.Node f = n.forward[i];
            long expected = f == null ? len - rank : rankOf.get(f) - rank;
            assertEquals(expected, n.span[i], "span at level " + i + " from rank " + rank);
            if (f != null && i > 0) {
                // Level i skips over nodes in order: the target is reachable and later.
                assertTrue(rankOf.get(f) > rank);
            }
        }
    }

    // ---- edge cases on their own ----

    @Test
    void emptyList() {
        SkipList sl = new SkipList(new SplittableRandom(1));
        assertNull(sl.first());
        assertNull(sl.last());
        assertEquals(0, sl.rank(1, Key.of("a")));
        assertNull(sl.nodeAtRank(1));
        assertTrue(!sl.delete(1, Key.of("a")));
        assertNull(sl.firstInRange(new SkipList.Range(0, false, 10, false)));
    }

    @Test
    void oneElement() {
        ZSetValue z = new ZSetValue(new SkipList(new SplittableRandom(2)));
        z.put(Key.of("a"), 5);
        checkInvariants(z, List.of(new Entry(5, Key.of("a"))), new SplittableRandom(3));
        assertTrue(z.remove(Key.of("a")));
        checkInvariants(z, List.of(), new SplittableRandom(3));
        assertEquals(1, z.list.level, "level shrinks back to 1");
    }

    @Test
    void allEqualScoresOrderByUnsignedMemberBytes() {
        ZSetValue z = new ZSetValue(new SkipList(new SplittableRandom(4)));
        Key high = Key.wrap(new byte[] {(byte) 0xff});
        Key mid = Key.wrap(new byte[] {(byte) 0x80});
        for (Key k : List.of(high, Key.of("b"), mid, Key.of("A"), Key.of("a"))) {
            z.put(k, 1);
        }
        List<Entry> expected = List.of(new Entry(1, Key.of("A")), new Entry(1, Key.of("a")), new Entry(1, Key.of("b")),
                new Entry(1, mid), new Entry(1, high));
        checkInvariants(z, expected, new SplittableRandom(5));
    }

    @Test
    void scoreRanges() {
        ZSetValue z = new ZSetValue(new SkipList(new SplittableRandom(6)));
        for (int i = 0; i < 100; i++) {
            z.put(Key.of("m" + i), i);
        }
        assertEquals(10, z.list.firstInRange(new SkipList.Range(10, false, 20, false)).score());
        assertEquals(11, z.list.firstInRange(new SkipList.Range(10, true, 20, false)).score());
        assertEquals(20, z.list.lastInRange(new SkipList.Range(10, false, 20, false)).score());
        assertEquals(19, z.list.lastInRange(new SkipList.Range(10, false, 20, true)).score());
        assertNull(z.list.firstInRange(new SkipList.Range(200, false, 300, false)));
        assertNull(z.list.firstInRange(new SkipList.Range(5, true, 5, false)));
        assertNull(z.list.firstInRange(new SkipList.Range(5.5, false, 5.7, false)));
    }

    @Test
    void averageLevelIsAboutOneAndAThird() {
        SkipList sl = new SkipList(new SplittableRandom(7));
        long levels = 0;
        int n = 200_000;
        for (int i = 0; i < n; i++) {
            levels += sl.randomLevel();
        }
        double avg = levels / (double) n;
        assertTrue(avg > 1.30 && avg < 1.36, "average level " + avg);
    }
}
