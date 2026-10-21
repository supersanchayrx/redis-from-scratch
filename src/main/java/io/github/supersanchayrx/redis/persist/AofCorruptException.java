package io.github.supersanchayrx.redis.persist;

/** The AOF is damaged somewhere other than its tail. The server refuses to start rather than drop data. */
public final class AofCorruptException extends Exception {

    public AofCorruptException(String message) {
        super(message);
    }
}
