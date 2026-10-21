package io.github.supersanchayrx.redis.ds;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LinkedListTest {

    private static List<Integer> toList(LinkedList<Integer> l) {
        List<Integer> out = new ArrayList<>();
        for (LinkedList.Node<Integer> n = l.head(); n != null; n = n.next()) {
            out.add(n.value());
        }
        List<Integer> back = new ArrayList<>();
        for (LinkedList.Node<Integer> n = l.tail(); n != null; n = n.prev()) {
            back.add(0, n.value());
        }
        assertEquals(out, back, "prev links disagree with next links");
        assertEquals(out.size(), l.size());
        return out;
    }

    @Test
    void randomOpsMatchArrayDeque() {
        SplittableRandom rnd = new SplittableRandom(99);
        LinkedList<Integer> l = new LinkedList<>();
        Deque<Integer> model = new ArrayDeque<>();
        for (int i = 0; i < 20_000; i++) {
            switch (rnd.nextInt(5)) {
                case 0 -> { l.pushFront(i); model.addFirst(i); }
                case 1 -> { l.pushBack(i); model.addLast(i); }
                case 2 -> assertEquals(model.pollFirst(), l.popFront());
                case 3 -> assertEquals(model.pollLast(), l.popBack());
                default -> {
                    if (!model.isEmpty()) {
                        int idx = rnd.nextInt(model.size());
                        assertEquals(new ArrayList<>(model).get(idx), l.get(idx));
                    }
                }
            }
            if (i % 500 == 0) {
                assertEquals(new ArrayList<>(model), toList(l));
            }
        }
        assertEquals(new ArrayList<>(model), toList(l));
    }

    @Test
    void emptyAndOutOfRange() {
        LinkedList<Integer> l = new LinkedList<>();
        assertNull(l.popFront());
        assertNull(l.popBack());
        assertNull(l.nodeAt(0));
        l.pushBack(1);
        assertNull(l.nodeAt(1));
        assertNull(l.nodeAt(-1));
        assertEquals(1, l.popBack());
        assertNull(l.head());
        assertNull(l.tail());
    }

    @Test
    void removeMatchingFromEitherEnd() {
        LinkedList<Integer> l = new LinkedList<>();
        for (int v : new int[] {1, 2, 1, 3, 1, 4, 1}) {
            l.pushBack(v);
        }
        assertEquals(2, l.removeMatching(v -> v == 1, 2));
        assertEquals(List.of(2, 3, 1, 4, 1), toList(l));
        assertEquals(1, l.removeMatching(v -> v == 1, -1));
        assertEquals(List.of(2, 3, 1, 4), toList(l));
        assertEquals(1, l.removeMatching(v -> v == 1, 0));
        assertEquals(List.of(2, 3, 4), toList(l));
    }

    @Test
    void trim() {
        LinkedList<Integer> l = new LinkedList<>();
        for (int i = 0; i < 10; i++) {
            l.pushBack(i);
        }
        l.trim(2, 5);
        assertEquals(List.of(2, 3, 4, 5), toList(l));
    }
}
