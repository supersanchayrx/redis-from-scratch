package io.github.supersanchayrx.redis.store;

import java.util.LinkedHashMap;

/**
 * A hash value. Insertion order is kept so HGETALL on a small hash lists fields in the order Redis's
 * listpack encoding would.
 */
public final class HashValue implements Value {

    public final LinkedHashMap<Key, byte[]> map = new LinkedHashMap<>();

    @Override
    public String typeName() {
        return "hash";
    }
}
