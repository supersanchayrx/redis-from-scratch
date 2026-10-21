package io.github.supersanchayrx.redis;

import io.github.supersanchayrx.redis.commands.CommandTable;
import io.github.supersanchayrx.redis.commands.Context;
import io.github.supersanchayrx.redis.persist.Aof;
import io.github.supersanchayrx.redis.persist.AofCorruptException;
import io.github.supersanchayrx.redis.persist.AofLoader;
import io.github.supersanchayrx.redis.pubsub.PubSub;
import io.github.supersanchayrx.redis.server.Client;
import io.github.supersanchayrx.redis.server.EventLoop;
import io.github.supersanchayrx.redis.store.ExpiryCycle;
import io.github.supersanchayrx.redis.store.Keyspace;
import io.github.supersanchayrx.redis.util.Bytes;

import java.io.IOException;

/** Wires the components together and implements the loop's hooks. Startup order: load the AOF, then bind. */
public final class Server implements EventLoop.Handler {

    private static final byte[] DEL = Bytes.utf8("DEL");

    private final Config config;
    private final Keyspace ks = new Keyspace();
    private final Aof aof;
    private final EventLoop loop;
    private final PubSub pubsub;
    private final Context ctx;
    private final ExpiryCycle expiry;

    public Server(Config config) {
        this(config, Clock.SYSTEM);
    }

    public Server(Config config, Clock clock) {
        this.config = config;
        this.aof = new Aof(config, clock);
        this.loop = new EventLoop(clock);
        this.pubsub = new PubSub(config, loop);
        this.ctx = new Context(config, clock, ks, aof, pubsub, CommandTable.standard(), loop);
        this.expiry = new ExpiryCycle(ks, clock, config.hz);
        ctx.expiry = expiry;
        loop.setHandler(this);
        // Every expiry, lazy or active, is logged as a DEL so a replay never resurrects the key.
        ks.setListener(key -> aof.append(new byte[][] {DEL, key.bytes()}));
    }

    /** Replays the AOF if appendonly is on. Must run before {@link #bind()}. */
    public AofLoader.Result loadAof() throws IOException, AofCorruptException {
        if (!config.appendonly) {
            return null;
        }
        return AofLoader.load(aof.path(), ctx);
    }

    /** Opens the AOF for appending, binds the port and schedules the timers. */
    public void bind() throws IOException {
        aof.open();
        loop.bind(config.bind, config.port);
        long now = ctx.clock.nanos();
        loop.timers().every(Math.max(1, 1000 / config.hz), now, expiry::run);
        if (config.appendonly && config.appendfsync == Config.Fsync.EVERYSEC) {
            loop.timers().every(1000, now, aof::fsyncIfNeeded);
        }
        loop.timers().every(1000, now, () -> pubsub.checkSoftLimits(ctx.clock.nanos()));
    }

    public void run() {
        loop.run();
    }

    public void stop() {
        loop.requestStop();
    }

    public boolean isRunning() {
        return loop.isRunning();
    }

    public int port() {
        return loop.port();
    }

    public Context context() {
        return ctx;
    }

    // ---- EventLoop.Handler ----

    @Override
    public void onCommand(Client c, byte[][] argv) {
        ctx.table.execute(ctx, c, argv);
    }

    @Override
    public void onClose(Client c) {
        pubsub.removeClient(c);
    }

    @Override
    public void beforeSleep() {
        aof.flush();
    }

    @Override
    public void onShutdown() {
        aof.close();
    }
}
