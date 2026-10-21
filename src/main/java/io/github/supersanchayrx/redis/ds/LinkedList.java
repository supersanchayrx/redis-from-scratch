package io.github.supersanchayrx.redis.ds;

import java.util.function.Predicate;

/**
 * A doubly linked list with head, tail and size: O(1) push and pop at both ends, and index access that
 * walks from whichever end is nearer.
 *
 * <p>Real Redis uses a quicklist (a linked list of small packed arrays) instead, because one heap node per
 * element costs memory and cache misses. This is the textbook structure the quicklist improves on.
 */
public final class LinkedList<E> {

    public static final class Node<E> {
        E value;
        Node<E> prev;
        Node<E> next;

        Node(E value) {
            this.value = value;
        }

        public E value() {
            return value;
        }

        public Node<E> next() {
            return next;
        }

        public Node<E> prev() {
            return prev;
        }
    }

    private Node<E> head;
    private Node<E> tail;
    private int size;

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public Node<E> head() {
        return head;
    }

    public Node<E> tail() {
        return tail;
    }

    public void pushFront(E value) {
        Node<E> n = new Node<>(value);
        n.next = head;
        if (head != null) {
            head.prev = n;
        } else {
            tail = n;
        }
        head = n;
        size++;
    }

    public void pushBack(E value) {
        Node<E> n = new Node<>(value);
        n.prev = tail;
        if (tail != null) {
            tail.next = n;
        } else {
            head = n;
        }
        tail = n;
        size++;
    }

    /** Removes and returns the first element, or null if the list is empty. */
    public E popFront() {
        if (head == null) {
            return null;
        }
        Node<E> n = head;
        unlink(n);
        return n.value;
    }

    /** Removes and returns the last element, or null if the list is empty. */
    public E popBack() {
        if (tail == null) {
            return null;
        }
        Node<E> n = tail;
        unlink(n);
        return n.value;
    }

    /** The node at a 0-based index, walking from the nearer end; null if out of range. */
    public Node<E> nodeAt(int index) {
        if (index < 0 || index >= size) {
            return null;
        }
        Node<E> n;
        if (index < size / 2) {
            n = head;
            for (int i = 0; i < index; i++) {
                n = n.next;
            }
        } else {
            n = tail;
            for (int i = size - 1; i > index; i--) {
                n = n.prev;
            }
        }
        return n;
    }

    public E get(int index) {
        Node<E> n = nodeAt(index);
        return n == null ? null : n.value;
    }

    public void set(Node<E> node, E value) {
        node.value = value;
    }

    public void unlink(Node<E> n) {
        if (n.prev != null) {
            n.prev.next = n.next;
        } else {
            head = n.next;
        }
        if (n.next != null) {
            n.next.prev = n.prev;
        } else {
            tail = n.prev;
        }
        n.prev = null;
        n.next = null;
        size--;
    }

    /**
     * Removes up to {@code count} elements matching {@code test}: from the head if count &gt; 0, from the
     * tail if count &lt; 0, all of them if count is 0 (LREM's semantics). Returns how many were removed.
     */
    public int removeMatching(Predicate<E> test, long count) {
        int removed = 0;
        long limit = count == 0 ? Long.MAX_VALUE : Math.abs(count);
        Node<E> n = count >= 0 ? head : tail;
        while (n != null && removed < limit) {
            Node<E> following = count >= 0 ? n.next : n.prev;
            if (test.test(n.value)) {
                unlink(n);
                removed++;
            }
            n = following;
        }
        return removed;
    }

    /** Keeps only indices [start, stop] (both valid and start &lt;= stop), dropping the rest. */
    public void trim(int start, int stop) {
        for (int i = 0; i < start; i++) {
            unlink(head);
        }
        int keep = stop - start + 1;
        while (size > keep) {
            unlink(tail);
        }
    }

    public void clear() {
        head = null;
        tail = null;
        size = 0;
    }
}
