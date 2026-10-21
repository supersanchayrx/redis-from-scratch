package io.github.supersanchayrx.redis.commands;

import io.github.supersanchayrx.redis.protocol.Resp;
import io.github.supersanchayrx.redis.server.Client;
import io.github.supersanchayrx.redis.store.Key;
import io.github.supersanchayrx.redis.store.StringValue;
import io.github.supersanchayrx.redis.store.Value;
import io.github.supersanchayrx.redis.util.Bytes;

import static io.github.supersanchayrx.redis.commands.CommandSpec.FAST;
import static io.github.supersanchayrx.redis.commands.CommandSpec.READONLY;
import static io.github.supersanchayrx.redis.commands.CommandSpec.WRITE;

/** GET, SET, SETNX, SETEX, PSETEX, GETSET, GETDEL, MGET, MSET, MSETNX, INCR/DECR(BY), APPEND, STRLEN. */
final class StringCommands {

    private static final byte[] SET = Bytes.utf8("SET");
    private static final byte[] PXAT = Bytes.utf8("PXAT");

    private StringCommands() {
    }

    static void register(CommandTable t) {
        t.add("GET", 2, READONLY | FAST, StringCommands::get);
        t.add("SET", -3, WRITE, StringCommands::set);
        t.add("SETNX", 3, WRITE | FAST, StringCommands::setnx);
        t.add("SETEX", 4, WRITE, (ctx, c, a) -> setex(ctx, c, a, true, "setex"));
        t.add("PSETEX", 4, WRITE, (ctx, c, a) -> setex(ctx, c, a, false, "psetex"));
        t.add("GETSET", 3, WRITE | FAST, StringCommands::getset);
        t.add("GETDEL", 2, WRITE | FAST, StringCommands::getdel);
        t.add("MGET", -2, READONLY | FAST, StringCommands::mget);
        t.add("MSET", -3, WRITE, StringCommands::mset);
        t.add("MSETNX", -3, WRITE, StringCommands::msetnx);
        t.add("INCR", 2, WRITE | FAST, (ctx, c, a) -> incrBy(ctx, c, a[1], 1));
        t.add("DECR", 2, WRITE | FAST, (ctx, c, a) -> incrBy(ctx, c, a[1], -1));
        t.add("INCRBY", 3, WRITE | FAST, (ctx, c, a) -> incrBy(ctx, c, a[1], Args.parseLong(a[2])));
        t.add("DECRBY", 3, WRITE | FAST, StringCommands::decrby);
        t.add("APPEND", 3, WRITE | FAST, StringCommands::append);
        t.add("STRLEN", 2, READONLY | FAST, StringCommands::strlen);
    }

    private static StringValue getString(Context ctx, Key k) {
        return ctx.ks.get(k, StringValue.class);
    }

    private static void get(Context ctx, Client c, byte[][] a) {
        StringValue v = getString(ctx, Args.key(a[1]));
        c.out.bulk(v == null ? null : v.bytes());
    }

    /** SET key value [NX | XX] [GET] [EX s | PX ms | EXAT s | PXAT ms | KEEPTTL]. */
    private static void set(Context ctx, Client c, byte[][] a) {
        boolean nx = false;
        boolean xx = false;
        boolean get = false;
        boolean keepTtl = false;
        boolean relative = false;
        long expireAt = -1;
        for (int i = 3; i < a.length; i++) {
            byte[] opt = a[i];
            boolean hasNext = i + 1 < a.length;
            if (Args.eq(opt, "NX") && !xx) {
                nx = true;
            } else if (Args.eq(opt, "XX") && !nx) {
                xx = true;
            } else if (Args.eq(opt, "GET")) {
                get = true;
            } else if (Args.eq(opt, "KEEPTTL") && expireAt == -1) {
                keepTtl = true;
            } else if (!keepTtl && expireAt == -1 && hasNext
                    && (Args.eq(opt, "EX") || Args.eq(opt, "PX") || Args.eq(opt, "EXAT") || Args.eq(opt, "PXAT"))) {
                boolean seconds = Args.eq(opt, "EX") || Args.eq(opt, "EXAT");
                relative = Args.eq(opt, "EX") || Args.eq(opt, "PX");
                expireAt = expireMillis(ctx, a[++i], seconds, relative, "set");
            } else {
                throw CommandException.SYNTAX;
            }
        }
        Key key = Args.key(a[1]);
        StringValue old = get ? getString(ctx, key) : null; // WRONGTYPE before anything changes
        boolean found = get ? old != null : ctx.ks.exists(key);
        if ((nx && found) || (xx && !found)) {
            if (get) {
                c.out.bulk(old == null ? null : old.bytes());
            } else {
                c.out.nullBulk();
            }
            return;
        }
        store(ctx, key, a[1], a[2], expireAt, relative, keepTtl);
        if (get) {
            c.out.bulk(old == null ? null : old.bytes());
        } else {
            c.out.raw(Resp.OK);
        }
    }

    /**
     * Stores the value and its TTL, and logs the effect: a relative TTL is logged as
     * {@code SET key value PXAT <absolute ms>} (as t_string.c does), an expiry already in the past as DEL.
     */
    private static void store(Context ctx, Key key, byte[] rawKey, byte[] value, long expireAt, boolean relative,
                              boolean keepTtl) {
        if (expireAt != -1 && expireAt <= ctx.ks.now() && !ctx.isLoading()) {
            ctx.ks.delete(key);
            ctx.propagate(ExpireCommands.DEL, rawKey);
            return;
        }
        if (keepTtl) {
            ctx.ks.setKeepTtl(key, new StringValue(value));
        } else {
            ctx.ks.set(key, new StringValue(value));
        }
        if (expireAt != -1) {
            ctx.ks.setExpire(key, expireAt);
            if (relative) {
                ctx.propagate(SET, rawKey, value, PXAT, Bytes.ascii(expireAt));
            }
        }
    }

    /** An EX/PX/EXAT/PXAT argument as an absolute Unix-ms time, with Redis's range checks. */
    private static long expireMillis(Context ctx, byte[] arg, boolean seconds, boolean relative, String cmd) {
        long v = Args.parseLong(arg);
        if (v <= 0 || (seconds && v > Long.MAX_VALUE / 1000)) {
            throw CommandException.invalidExpire(cmd);
        }
        if (seconds) {
            v *= 1000;
        }
        if (relative) {
            if (v > Long.MAX_VALUE - ctx.ks.now()) {
                throw CommandException.invalidExpire(cmd);
            }
            v += ctx.ks.now();
        }
        return v;
    }

    private static void setnx(Context ctx, Client c, byte[][] a) {
        Key key = Args.key(a[1]);
        if (ctx.ks.exists(key)) {
            c.out.integer(0);
            return;
        }
        ctx.ks.set(key, new StringValue(a[2]));
        c.out.integer(1);
    }

    private static void setex(Context ctx, Client c, byte[][] a, boolean seconds, String name) {
        long at = expireMillis(ctx, a[2], seconds, true, name);
        store(ctx, Args.key(a[1]), a[1], a[3], at, true, false);
        c.out.raw(Resp.OK);
    }

    private static void getset(Context ctx, Client c, byte[][] a) {
        Key key = Args.key(a[1]);
        StringValue old = getString(ctx, key);
        ctx.ks.set(key, new StringValue(a[2]));
        c.out.bulk(old == null ? null : old.bytes());
    }

    private static void getdel(Context ctx, Client c, byte[][] a) {
        Key key = Args.key(a[1]);
        StringValue old = getString(ctx, key);
        if (old != null) {
            ctx.ks.delete(key);
        }
        c.out.bulk(old == null ? null : old.bytes());
    }

    /** MGET never raises WRONGTYPE: a key of another type reads as missing. */
    private static void mget(Context ctx, Client c, byte[][] a) {
        c.out.arrayHeader(a.length - 1);
        for (int i = 1; i < a.length; i++) {
            Value v = ctx.ks.get(Args.key(a[i]));
            c.out.bulk(v instanceof StringValue s ? s.bytes() : null);
        }
    }

    private static void mset(Context ctx, Client c, byte[][] a) {
        if (a.length % 2 == 0) {
            throw CommandException.wrongArgs("mset");
        }
        for (int i = 1; i < a.length; i += 2) {
            ctx.ks.set(Args.key(a[i]), new StringValue(a[i + 1]));
        }
        c.out.raw(Resp.OK);
    }

    private static void msetnx(Context ctx, Client c, byte[][] a) {
        if (a.length % 2 == 0) {
            throw CommandException.wrongArgs("msetnx");
        }
        for (int i = 1; i < a.length; i += 2) {
            if (ctx.ks.exists(Args.key(a[i]))) {
                c.out.integer(0);
                return;
            }
        }
        for (int i = 1; i < a.length; i += 2) {
            ctx.ks.set(Args.key(a[i]), new StringValue(a[i + 1]));
        }
        c.out.integer(1);
    }

    /** INCR and friends: a missing key counts as 0, and the TTL is kept. */
    private static void incrBy(Context ctx, Client c, byte[] rawKey, long by) {
        Key key = Args.key(rawKey);
        StringValue v = getString(ctx, key);
        long cur;
        if (v == null) {
            cur = 0;
        } else {
            try {
                cur = Bytes.parseLong(v.bytes());
            } catch (Bytes.NotANumber e) {
                throw CommandException.NOT_INTEGER;
            }
        }
        if ((by < 0 && cur < 0 && by < Long.MIN_VALUE - cur) || (by > 0 && cur > 0 && by > Long.MAX_VALUE - cur)) {
            throw CommandException.OVERFLOW;
        }
        long next = cur + by;
        ctx.ks.setKeepTtl(key, new StringValue(Bytes.ascii(next)));
        c.out.integer(next);
    }

    private static void decrby(Context ctx, Client c, byte[][] a) {
        long by = Args.parseLong(a[2]);
        if (by == Long.MIN_VALUE) {
            throw new CommandException("ERR decrement would overflow");
        }
        incrBy(ctx, c, a[1], -by);
    }

    private static void append(Context ctx, Client c, byte[][] a) {
        Key key = Args.key(a[1]);
        StringValue v = getString(ctx, key);
        byte[] next;
        if (v == null) {
            next = a[2];
        } else {
            next = new byte[v.bytes().length + a[2].length];
            System.arraycopy(v.bytes(), 0, next, 0, v.bytes().length);
            System.arraycopy(a[2], 0, next, v.bytes().length, a[2].length);
        }
        ctx.ks.setKeepTtl(key, new StringValue(next));
        c.out.integer(next.length);
    }

    private static void strlen(Context ctx, Client c, byte[][] a) {
        StringValue v = getString(ctx, Args.key(a[1]));
        c.out.integer(v == null ? 0 : v.bytes().length);
    }
}
