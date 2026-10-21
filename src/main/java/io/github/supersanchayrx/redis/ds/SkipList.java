package io.github.supersanchayrx.redis.ds;

import io.github.supersanchayrx.redis.store.Key;

import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

/**
 * A skip list ordered by (score, member), with a span on every forward pointer, after Pugh (1990) and
 * Redis's t_zset.c.
 *
 * <p>A node's {@code span[i]} is the number of level-0 steps its level-i pointer jumps. When that pointer
 * is null, the span counts the steps to the end of the list. Summing spans along a search path gives a
 * node's rank, so ZRANK and "the node at index i" are O(log n) instead of a level-0 walk.
 *
 * <p>Ranks here are 1-based (the header is rank 0); ZRANK subtracts one.
 */
public final class SkipList {

    /** Redis's ZSKIPLIST_MAXLEVEL. */
    public static final int MAX_LEVEL = 32;
    /** Redis's ZSKIPLIST_P: each level holds about a quarter of the nodes of the one below. */
    public static final double P = 0.25;

    public static final class Node {
        final Key member;
        double score;
        Node backward;
        final Node[] forward;
        final long[] span;

        Node(int level, double score, Key member) {
            this.member = member;
            this.score = score;
            this.forward = new Node[level];
            this.span = new long[level];
        }

        public Key member() {
            return member;
        }

        public double score() {
            return score;
        }

        /** The next node in order (level 0), or null at the tail. */
        public Node next() {
            return forward[0];
        }

        /** The previous node in order, or null at the first node. */
        public Node prev() {
            return backward;
        }

        public int levels() {
            return forward.length;
        }
    }

    final Node header = new Node(MAX_LEVEL, 0, null);
    Node tail;
    long length;
    /** The number of levels in use; the header's pointers above this are unused. */
    int level = 1;

    private final RandomGenerator rng;

    // Scratch arrays for the search path. Safe to share: only the event-loop thread touches a skip list.
    private final Node[] update = new Node[MAX_LEVEL];
    private final long[] rank = new long[MAX_LEVEL];

    public SkipList() {
        this(new SplittableRandom());
    }

    public SkipList(RandomGenerator rng) {
        this.rng = rng;
    }

    public long length() {
        return length;
    }

    /** The lowest-ordered node, or null if empty. */
    public Node first() {
        return header.forward[0];
    }

    /** The highest-ordered node, or null if empty. */
    public Node last() {
        return tail;
    }

    /** 1 with probability 3/4, 2 with 3/16, and so on, capped at MAX_LEVEL. The mean is 1/(1-P), about 1.33. */
    int randomLevel() {
        int lvl = 1;
        while (lvl < MAX_LEVEL && rng.nextDouble() < P) {
            lvl++;
        }
        return lvl;
    }

    /** Does {@code n} sort strictly before (score, member)? Ties on score go by member bytes, unsigned. */
    private static boolean before(Node n, double score, Key member) {
        return n.score < score || (n.score == score && n.member.compareTo(member) < 0);
    }

    /**
     * Inserts a member that isn't already present (the caller's dict guarantees that) and returns its node.
     */
    public Node insert(double score, Key member) {
        // 1. The search path: the last node before the new one at each level, and its rank.
        Node x = header;
        for (int i = level - 1; i >= 0; i--) {
            rank[i] = (i == level - 1) ? 0 : rank[i + 1];
            while (x.forward[i] != null && before(x.forward[i], score, member)) {
                rank[i] += x.span[i];
                x = x.forward[i];
            }
            update[i] = x;
        }

        // 2. New levels start at the header, whose span there covers the whole list.
        int lvl = randomLevel();
        if (lvl > level) {
            for (int i = level; i < lvl; i++) {
                rank[i] = 0;
                update[i] = header;
                header.span[i] = length;
            }
            level = lvl;
        }

        // 3. Splice in at each of the node's levels. rank[0] - rank[i] is how far update[i] is behind
        //    update[0]; the new node sits one step after update[0], which splits update[i]'s old span.
        x = new Node(lvl, score, member);
        for (int i = 0; i < lvl; i++) {
            x.forward[i] = update[i].forward[i];
            update[i].forward[i] = x;
            x.span[i] = update[i].span[i] - (rank[0] - rank[i]);
            update[i].span[i] = (rank[0] - rank[i]) + 1;
        }
        // 4. Pointers above the new node's height now jump over one more node.
        for (int i = lvl; i < level; i++) {
            update[i].span[i]++;
        }

        x.backward = (update[0] == header) ? null : update[0];
        if (x.forward[0] != null) {
            x.forward[0].backward = x;
        } else {
            tail = x;
        }
        length++;
        clearPath();
        return x;
    }

    /** Removes (score, member) if present. */
    public boolean delete(double score, Key member) {
        Node x = header;
        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null && before(x.forward[i], score, member)) {
                x = x.forward[i];
            }
            update[i] = x;
        }
        x = x.forward[0];
        boolean found = x != null && x.score == score && x.member.equals(member);
        if (found) {
            unlink(x);
        }
        clearPath();
        return found;
    }

    /** Unlinks {@code x}, given the search path to it in {@code update}. */
    private void unlink(Node x) {
        for (int i = 0; i < level; i++) {
            if (update[i].forward[i] == x) {
                // update[i] now jumps over x and on to where x jumped: x's span, minus x itself.
                update[i].span[i] += x.span[i] - 1;
                update[i].forward[i] = x.forward[i];
            } else {
                // A pointer passing over x: one fewer node underneath it.
                update[i].span[i] -= 1;
            }
        }
        if (x.forward[0] != null) {
            x.forward[0].backward = x.backward;
        } else {
            tail = x.backward;
        }
        while (level > 1 && header.forward[level - 1] == null) {
            level--;
        }
        length--;
    }

    /**
     * Moves an existing node to a new score and returns the node now holding the member. When the new
     * score keeps it between the same neighbours, the score changes in place (as Redis does); otherwise
     * it's a delete plus an insert.
     */
    public Node updateScore(Node node, double newScore) {
        Node next = node.forward[0];
        if ((node.backward == null || node.backward.score < newScore)
                && (next == null || next.score > newScore)) {
            node.score = newScore;
            return node;
        }
        Key member = node.member;
        delete(node.score, member);
        return insert(newScore, member);
    }

    /** The 1-based rank of (score, member), or 0 if it isn't in the list. */
    public long rank(double score, Key member) {
        long r = 0;
        Node x = header;
        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null
                    && (x.forward[i].score < score
                    || (x.forward[i].score == score && x.forward[i].member.compareTo(member) <= 0))) {
                r += x.span[i];
                x = x.forward[i];
            }
            if (x.member != null && x.score == score && x.member.equals(member)) {
                return r;
            }
        }
        return 0;
    }

    /** The node at a 1-based rank, or null if out of range. */
    public Node nodeAtRank(long r) {
        if (r < 1 || r > length) {
            return null;
        }
        long traversed = 0;
        Node x = header;
        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null && traversed + x.span[i] <= r) {
                traversed += x.span[i];
                x = x.forward[i];
            }
            if (traversed == r) {
                return x;
            }
        }
        return null;
    }

    /** A score interval; {@code minExclusive} means "(min" in Redis's syntax. */
    public record Range(double min, boolean minExclusive, double max, boolean maxExclusive) {

        boolean aboveMin(double s) {
            return minExclusive ? s > min : s >= min;
        }

        boolean belowMax(double s) {
            return maxExclusive ? s < max : s <= max;
        }

        boolean isEmpty() {
            return min > max || (min == max && (minExclusive || maxExclusive));
        }
    }

    /** The first node whose score is inside the range, or null. */
    public Node firstInRange(Range r) {
        if (r.isEmpty() || !hasAnyInRange(r)) {
            return null;
        }
        Node x = header;
        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null && !r.aboveMin(x.forward[i].score)) {
                x = x.forward[i];
            }
        }
        x = x.forward[0];
        return (x != null && r.belowMax(x.score)) ? x : null;
    }

    /** The last node whose score is inside the range, or null. */
    public Node lastInRange(Range r) {
        if (r.isEmpty() || !hasAnyInRange(r)) {
            return null;
        }
        Node x = header;
        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null && r.belowMax(x.forward[i].score)) {
                x = x.forward[i];
            }
        }
        return (x != header && r.aboveMin(x.score)) ? x : null;
    }

    private boolean hasAnyInRange(Range r) {
        return tail != null && r.aboveMin(tail.score) && header.forward[0] != null
                && r.belowMax(header.forward[0].score);
    }

    /** Drops references held by the scratch arrays, so deleted nodes can be collected. */
    private void clearPath() {
        Arrays.fill(update, null);
    }
}
