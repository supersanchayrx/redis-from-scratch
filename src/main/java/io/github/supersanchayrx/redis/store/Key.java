package io.github.supersanchayrx.redis.store;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * An immutable, binary-safe key. A raw {@code byte[]} can't be a {@code HashMap} key (its equals is
 * identity), and Java bytes are signed while Redis compares with {@code memcmp}, so this wraps the bytes
 * with content equality, a cached hash and unsigned ordering. Hash fields and sorted-set members use it too.
 */
public final class Key implements Comparable<Key> {

    private final byte[] bytes;
    private final int hash;

    private Key(byte[] bytes) {
        this.bytes = bytes;
        this.hash = Arrays.hashCode(bytes);
    }

    /** Copies the bytes, for callers whose array may be reused. */
    public static Key copyOf(byte[] bytes) {
        return new Key(bytes.clone());
    }

    /**
     * Takes ownership of the array without copying. The parser hands every argument out as a fresh array
     * that nothing else writes to, so command handlers use this.
     */
    public static Key wrap(byte[] bytes) {
        return new Key(bytes);
    }

    public static Key of(String s) {
        return new Key(s.getBytes(StandardCharsets.UTF_8));
    }

    /** The bytes themselves. Callers must not modify them. */
    public byte[] bytes() {
        return bytes;
    }

    public int length() {
        return bytes.length;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Key k && hash == k.hash && Arrays.equals(bytes, k.bytes);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public int compareTo(Key o) {
        return Arrays.compareUnsigned(bytes, o.bytes);
    }

    /** For logs only: the bytes decoded as UTF-8 with replacement characters. */
    @Override
    public String toString() {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
