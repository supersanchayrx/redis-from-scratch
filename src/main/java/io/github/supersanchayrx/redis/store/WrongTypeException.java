package io.github.supersanchayrx.redis.store;

/** A command met a key holding another type. The dispatcher turns it into Redis's WRONGTYPE error. */
public final class WrongTypeException extends RuntimeException {

    public static final WrongTypeException INSTANCE = new WrongTypeException();

    public static final String MESSAGE = "WRONGTYPE Operation against a key holding the wrong kind of value";

    private WrongTypeException() {
        super(MESSAGE, null, false, false);
    }
}
