package io.github.supersanchayrx.redis.store;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.random.RandomGenerator;

/**
 * The expires table: key to absolute Unix-ms expiry time, plus an indexable array of the same keys so the
 * active cycle can pick a random volatile key in O(1). A {@code HashMap} has no "random entry", and
 * iterating it from the start every tick would keep testing the same keys.
 *
 * <p>Removal moves the last array element into the freed slot, so add, remove and sample are all O(1).
 */
public final class Expires {

    private static final class Slot {
        long at;
        int index;

        Slot(long at, int index) {
            this.at = at;
            this.index = index;
        }
    }

    private final HashMap<Key, Slot> slots = new HashMap<>();
    private final ArrayList<Key> keys = new ArrayList<>();

    public int size() {
        return keys.size();
    }

    public boolean isEmpty() {
        return keys.isEmpty();
    }

    /** The key's expiry time in Unix ms, or -1 if it has none. */
    public long get(Key key) {
        Slot s = slots.get(key);
        return s == null ? -1 : s.at;
    }

    public void set(Key key, long at) {
        Slot s = slots.get(key);
        if (s != null) {
            s.at = at;
            return;
        }
        slots.put(key, new Slot(at, keys.size()));
        keys.add(key);
    }

    /** Returns true if the key had an expiry. */
    public boolean remove(Key key) {
        Slot s = slots.remove(key);
        if (s == null) {
            return false;
        }
        int last = keys.size() - 1;
        if (s.index != last) {
            Key moved = keys.get(last);
            keys.set(s.index, moved);
            slots.get(moved).index = s.index;
        }
        keys.remove(last);
        return true;
    }

    /** A uniformly random volatile key, or null if there are none. */
    public Key random(RandomGenerator rng) {
        int n = keys.size();
        return n == 0 ? null : keys.get(rng.nextInt(n));
    }

    public void clear() {
        slots.clear();
        keys.clear();
    }

    /** For tests: every slot's index points back at its own key. */
    boolean consistent() {
        if (slots.size() != keys.size()) {
            return false;
        }
        for (int i = 0; i < keys.size(); i++) {
            Slot s = slots.get(keys.get(i));
            if (s == null || s.index != i) {
                return false;
            }
        }
        return true;
    }
}
