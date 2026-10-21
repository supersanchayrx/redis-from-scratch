package io.github.supersanchayrx.redis.store;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;

/**
 * The key to value map and its expires table. Every handler goes through this API, never through the map,
 * so lazy expiry and empty-aggregate deletion can't be skipped somewhere.
 *
 * <p>Time is a per-command snapshot ({@link #setTime}): a command never sees a key alive on one line and
 * dead on the next. Redis does the same.
 */
public final class Keyspace {

    /** Told about every key removed by expiry, lazy or active (the AOF logs a DEL for it). */
    public interface ExpiryListener {
        void expired(Key key);
    }

    private final HashMap<Key, Value> data = new HashMap<>();
    private final Expires expires = new Expires();
    private ExpiryListener listener = key -> { };

    private long now;
    /**
     * While the AOF loads, nothing expires: a key can be past its PXAT now and still be read by a later
     * command in the file that ran while it was alive. Redis skips expiry during loading for this reason.
     */
    private boolean loading;
    /** Bumped by every change; the dispatcher logs a write command to the AOF only if it moved. */
    private long dirty;
    private long expiredKeys;

    public void setListener(ExpiryListener listener) {
        this.listener = listener;
    }

    public void setTime(long nowMs) {
        this.now = nowMs;
    }

    public long now() {
        return now;
    }

    public void setLoading(boolean loading) {
        this.loading = loading;
    }

    public boolean isLoading() {
        return loading;
    }

    public long dirty() {
        return dirty;
    }

    /** Records an in-place change to an aggregate (LPUSH onto an existing list, and so on). */
    public void modified() {
        dirty++;
    }

    public long expiredKeys() {
        return expiredKeys;
    }

    // ---- lookups ----

    /** The live value, or null. An expired key is deleted on the way. */
    public Value get(Key key) {
        expireIfNeeded(key);
        return data.get(key);
    }

    /** The live value if it has type {@code type}; null if missing; WRONGTYPE otherwise. */
    public <T extends Value> T get(Key key, Class<T> type) {
        Value v = get(key);
        if (v == null) {
            return null;
        }
        if (!type.isInstance(v)) {
            throw WrongTypeException.INSTANCE;
        }
        return type.cast(v);
    }

    public boolean exists(Key key) {
        return get(key) != null;
    }

    /** Number of keys, including expired ones not collected yet (Redis's DBSIZE counts those too). */
    public int size() {
        return data.size();
    }

    public int volatileCount() {
        return expires.size();
    }

    /** Every key that hasn't expired. Expired ones are skipped but not deleted, like Redis's KEYS. */
    public void forEachLiveKey(Consumer<Key> action) {
        for (Map.Entry<Key, Value> e : data.entrySet()) {
            if (!isExpired(e.getKey())) {
                action.accept(e.getKey());
            }
        }
    }

    // ---- writes ----

    /** Stores a value and clears any TTL: SET's semantics. */
    public void set(Key key, Value value) {
        data.put(key, value);
        expires.remove(key);
        dirty++;
    }

    /** Stores a value and keeps any TTL: INCR's semantics. Only for a key that exists or was just checked. */
    public void setKeepTtl(Key key, Value value) {
        data.put(key, value);
        dirty++;
    }

    /** Adds a new aggregate for a key known to be missing (so it has no TTL). */
    public void add(Key key, Value value) {
        data.put(key, value);
        dirty++;
    }

    /** Deletes a key and its TTL. Returns false if it didn't exist (or had just expired). */
    public boolean delete(Key key) {
        expireIfNeeded(key);
        if (data.remove(key) == null) {
            return false;
        }
        expires.remove(key);
        dirty++;
        return true;
    }

    /** Deletes the key if its list, hash or sorted set has become empty: aggregates are never empty. */
    public void removeIfEmpty(Key key, Value value) {
        boolean empty = switch (value) {
            case ListValue l -> l.list.isEmpty();
            case HashValue h -> h.map.isEmpty();
            case ZSetValue z -> z.size() == 0;
            case StringValue s -> false;
        };
        if (empty) {
            data.remove(key);
            expires.remove(key);
            dirty++;
        }
    }

    public void flush() {
        data.clear();
        expires.clear();
        dirty++;
    }

    // ---- expiry ----

    /** The absolute expiry time in Unix ms, or -1 if the key has none. Doesn't check liveness. */
    public long expireAt(Key key) {
        return expires.get(key);
    }

    /** Sets a TTL on an existing key. */
    public void setExpire(Key key, long atMs) {
        expires.set(key, atMs);
        dirty++;
    }

    /** Removes a TTL. Returns true if there was one. */
    public boolean persist(Key key) {
        if (expires.remove(key)) {
            dirty++;
            return true;
        }
        return false;
    }

    /** True if the key has a TTL that has passed. Like Redis, a key is expired once now &gt; its time. */
    public boolean isExpired(Key key) {
        if (loading || expires.isEmpty()) {
            return false;
        }
        long at = expires.get(key);
        return at >= 0 && now > at;
    }

    /** Deletes the key if it has expired, telling the listener. Returns true if it was deleted. */
    public boolean expireIfNeeded(Key key) {
        if (!isExpired(key)) {
            return false;
        }
        data.remove(key);
        expires.remove(key);
        expiredKeys++;
        listener.expired(key);
        return true;
    }

    /** A random key with a TTL, for the active expiry cycle. */
    Key randomVolatile(RandomGenerator rng) {
        return expires.random(rng);
    }

    Expires expires() {
        return expires;
    }
}
