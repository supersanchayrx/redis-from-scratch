package io.github.supersanchayrx.redis.store;

/**
 * A value in the keyspace. The sealed hierarchy makes the type check (and the WRONGTYPE error) one
 * {@code instanceof} in {@link Keyspace#get(Key, Class)}.
 */
public sealed interface Value permits StringValue, ListValue, HashValue, ZSetValue {

    /** What TYPE replies. */
    String typeName();
}
