package io.github.supersanchayrx.redis.protocol;

/**
 * Malformed input from a client. The message is Redis's own text (from networking.c), without the
 * {@code ERR } prefix. The loop replies with it and then closes the connection, as Redis does.
 */
public final class ProtocolException extends Exception {

    public ProtocolException(String message) {
        super("Protocol error: " + message, null, false, false);
    }
}
