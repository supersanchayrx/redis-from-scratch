package io.github.supersanchayrx.redis.commands;

import io.github.supersanchayrx.redis.server.Client;
import io.github.supersanchayrx.redis.store.Key;
import io.github.supersanchayrx.redis.util.Bytes;

import static io.github.supersanchayrx.redis.commands.CommandSpec.FAST;
import static io.github.supersanchayrx.redis.commands.CommandSpec.READONLY;
import static io.github.supersanchayrx.redis.commands.CommandSpec.WRITE;

/**
 * EXPIRE, PEXPIRE, EXPIREAT, PEXPIREAT, TTL, PTTL, EXPIRETIME, PEXPIRETIME, PERSIST.
 *
 * <p>Every successful EXPIRE variant is logged to the AOF as {@code PEXPIREAT key <absolute ms>}, or as
 * {@code DEL key} when the time is already past (then the key is deleted on the spot). Replaying a relative
 * TTL at a later restart would extend it.
 */
final class ExpireCommands {

    private static final byte[] PEXPIREAT = Bytes.utf8("PEXPIREAT");
    static final byte[] DEL = Bytes.utf8("DEL");

    private ExpireCommands() {
    }

    static void register(CommandTable t) {
        t.add("EXPIRE", -3, WRITE | FAST, (ctx, c, a) -> expire(ctx, c, a, ctx.ks.now(), true, "expire"));
        t.add("PEXPIRE", -3, WRITE | FAST, (ctx, c, a) -> expire(ctx, c, a, ctx.ks.now(), false, "pexpire"));
        t.add("EXPIREAT", -3, WRITE | FAST, (ctx, c, a) -> expire(ctx, c, a, 0, true, "expireat"));
        t.add("PEXPIREAT", -3, WRITE | FAST, (ctx, c, a) -> expire(ctx, c, a, 0, false, "pexpireat"));
        t.add("TTL", 2, READONLY | FAST, (ctx, c, a) -> ttl(ctx, c, a, false, false));
        t.add("PTTL", 2, READONLY | FAST, (ctx, c, a) -> ttl(ctx, c, a, true, false));
        t.add("EXPIRETIME", 2, READONLY | FAST, (ctx, c, a) -> ttl(ctx, c, a, false, true));
        t.add("PEXPIRETIME", 2, READONLY | FAST, (ctx, c, a) -> ttl(ctx, c, a, true, true));
        t.add("PERSIST", 2, WRITE | FAST, ExpireCommands::persist);
    }

    private static void expire(Context ctx, Client c, byte[][] a, long base, boolean seconds, String name) {
        Key key = Args.key(a[1]);
        long when = Args.parseLong(a[2]);
        boolean nx = false;
        boolean xx = false;
        boolean gt = false;
        boolean lt = false;
        for (int i = 3; i < a.length; i++) {
            if (Args.eq(a[i], "NX")) {
                nx = true;
            } else if (Args.eq(a[i], "XX")) {
                xx = true;
            } else if (Args.eq(a[i], "GT")) {
                gt = true;
            } else if (Args.eq(a[i], "LT")) {
                lt = true;
            } else {
                throw new CommandException("ERR Unsupported option " + Bytes.str(a[i]));
            }
        }
        if (nx && (xx || gt || lt)) {
            throw new CommandException("ERR NX and XX, GT or LT options at the same time are not compatible");
        }
        if (gt && lt) {
            throw new CommandException("ERR GT and LT options at the same time are not compatible");
        }
        if (seconds) {
            if (when > Long.MAX_VALUE / 1000 || when < Long.MIN_VALUE / 1000) {
                throw CommandException.invalidExpire(name);
            }
            when *= 1000;
        }
        if (when > Long.MAX_VALUE - base) {
            throw CommandException.invalidExpire(name);
        }
        when += base;

        if (!ctx.ks.exists(key)) {
            c.out.integer(0);
            return;
        }
        long current = ctx.ks.expireAt(key);
        // GT and LT treat a key with no TTL as having an infinite one.
        if ((nx && current != -1) || (xx && current == -1)
                || (gt && (current == -1 || when <= current)) || (lt && current != -1 && when >= current)) {
            c.out.integer(0);
            return;
        }
        if (when <= ctx.ks.now() && !ctx.isLoading()) {
            // A time in the past deletes the key, and the effect logged is the delete.
            ctx.ks.delete(key);
            ctx.propagate(DEL, a[1]);
        } else {
            ctx.ks.setExpire(key, when);
            ctx.propagate(PEXPIREAT, a[1], Bytes.ascii(when));
        }
        c.out.integer(1);
    }

    private static void ttl(Context ctx, Client c, byte[][] a, boolean ms, boolean absolute) {
        Key key = Args.key(a[1]);
        if (!ctx.ks.exists(key)) {
            c.out.integer(-2);
            return;
        }
        long at = ctx.ks.expireAt(key);
        if (at == -1) {
            c.out.integer(-1);
            return;
        }
        long t = absolute ? at : at - ctx.ks.now();
        if (t < 0) {
            t = 0;
        }
        // Seconds are rounded to the nearest second, as Redis does.
        c.out.integer(ms ? t : (t + 500) / 1000);
    }

    private static void persist(Context ctx, Client c, byte[][] a) {
        Key key = Args.key(a[1]);
        c.out.integer(ctx.ks.exists(key) && ctx.ks.persist(key) ? 1 : 0);
    }
}
