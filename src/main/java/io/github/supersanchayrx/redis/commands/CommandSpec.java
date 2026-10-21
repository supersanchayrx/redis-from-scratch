package io.github.supersanchayrx.redis.commands;

import io.github.supersanchayrx.redis.server.Client;

import java.util.Locale;

/**
 * One row of the command table. Arity follows Redis's convention: N &gt; 0 means exactly N arguments
 * including the name; -N means at least N.
 */
public record CommandSpec(String name, int arity, int flags, Handler handler) {

    /** Changes data: logged to the AOF when it changed something. */
    public static final int WRITE = 1;
    public static final int READONLY = 1 << 1;
    /** Allowed in RESP2 subscribed mode. */
    public static final int PUBSUB = 1 << 2;
    public static final int FAST = 1 << 3;

    @FunctionalInterface
    public interface Handler {
        void run(Context ctx, Client c, byte[][] argv);
    }

    public CommandSpec {
        name = name.toLowerCase(Locale.ROOT);
    }

    public boolean arityOk(int argc) {
        return arity > 0 ? argc == arity : argc >= -arity;
    }

    public boolean is(int flag) {
        return (flags & flag) != 0;
    }
}
