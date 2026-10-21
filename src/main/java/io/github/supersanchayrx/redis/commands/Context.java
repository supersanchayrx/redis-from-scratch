package io.github.supersanchayrx.redis.commands;

import io.github.supersanchayrx.redis.Clock;
import io.github.supersanchayrx.redis.Config;
import io.github.supersanchayrx.redis.persist.Aof;
import io.github.supersanchayrx.redis.pubsub.PubSub;
import io.github.supersanchayrx.redis.server.EventLoop;
import io.github.supersanchayrx.redis.store.ExpiryCycle;
import io.github.supersanchayrx.redis.store.Keyspace;

/** Everything a command handler can reach. Only the event-loop thread uses it. */
public final class Context {

    public static final class Stats {
        public long commands;
    }

    public final Config config;
    public final Clock clock;
    public final Keyspace ks;
    public final Aof aof;
    public final PubSub pubsub;
    public final CommandTable table;
    public final EventLoop loop;
    public final Stats stats = new Stats();
    public final long startMillis;
    /** The active expiry cycle, for INFO. Null in tests that don't run timers. */
    public ExpiryCycle expiry;

    /** Set by a handler that logged its own effect form, so the dispatcher doesn't log the original too. */
    boolean propagated;

    public Context(Config config, Clock clock, Keyspace ks, Aof aof, PubSub pubsub, CommandTable table, EventLoop loop) {
        this.config = config;
        this.clock = clock;
        this.ks = ks;
        this.aof = aof;
        this.pubsub = pubsub;
        this.table = table;
        this.loop = loop;
        this.startMillis = clock.millis();
    }

    /**
     * Logs {@code argv} to the AOF in place of the command as received. SET ... EX becomes SET ... PXAT,
     * EXPIRE becomes PEXPIREAT, and an EXPIRE into the past becomes DEL: a replay at any later time then
     * gives the same keyspace.
     */
    public void propagate(byte[]... argv) {
        aof.append(argv);
        propagated = true;
    }

    /** Marks the command as handled for the AOF without logging anything. */
    public void suppressPropagation() {
        propagated = true;
    }

    public boolean isLoading() {
        return ks.isLoading();
    }
}
