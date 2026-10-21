package io.github.supersanchayrx.redis.commands;

import io.github.supersanchayrx.redis.protocol.Resp;
import io.github.supersanchayrx.redis.server.Client;
import io.github.supersanchayrx.redis.store.HashValue;
import io.github.supersanchayrx.redis.store.Key;
import io.github.supersanchayrx.redis.util.Bytes;

import java.util.Map;

import static io.github.supersanchayrx.redis.commands.CommandSpec.FAST;
import static io.github.supersanchayrx.redis.commands.CommandSpec.READONLY;
import static io.github.supersanchayrx.redis.commands.CommandSpec.WRITE;

/** HSET, HMSET, HSETNX, HGET, HMGET, HDEL, HGETALL, HKEYS, HVALS, HLEN, HEXISTS, HSTRLEN, HINCRBY. */
final class HashCommands {

    private HashCommands() {
    }

    static void register(CommandTable t) {
        t.add("HSET", -4, WRITE | FAST, (ctx, c, a) -> hset(ctx, c, a, "hset"));
        t.add("HMSET", -4, WRITE | FAST, (ctx, c, a) -> hset(ctx, c, a, "hmset"));
        t.add("HSETNX", 4, WRITE | FAST, HashCommands::hsetnx);
        t.add("HGET", 3, READONLY | FAST, HashCommands::hget);
        t.add("HMGET", -3, READONLY | FAST, HashCommands::hmget);
        t.add("HDEL", -3, WRITE | FAST, HashCommands::hdel);
        t.add("HGETALL", 2, READONLY, (ctx, c, a) -> dump(ctx, c, a, true, true));
        t.add("HKEYS", 2, READONLY, (ctx, c, a) -> dump(ctx, c, a, true, false));
        t.add("HVALS", 2, READONLY, (ctx, c, a) -> dump(ctx, c, a, false, true));
        t.add("HLEN", 2, READONLY | FAST, HashCommands::hlen);
        t.add("HEXISTS", 3, READONLY | FAST, HashCommands::hexists);
        t.add("HSTRLEN", 3, READONLY | FAST, HashCommands::hstrlen);
        t.add("HINCRBY", 4, WRITE | FAST, HashCommands::hincrby);
    }

    private static HashValue getHash(Context ctx, Key k) {
        return ctx.ks.get(k, HashValue.class);
    }

    private static HashValue getOrCreate(Context ctx, Key k) {
        HashValue h = getHash(ctx, k);
        if (h == null) {
            h = new HashValue();
            ctx.ks.add(k, h);
        }
        return h;
    }

    /** HSET replies with the number of new fields (updates don't count); HMSET replies OK. */
    private static void hset(Context ctx, Client c, byte[][] a, String name) {
        if (a.length % 2 == 1) {
            throw CommandException.wrongArgs(name);
        }
        HashValue h = getOrCreate(ctx, Args.key(a[1]));
        long added = 0;
        for (int i = 2; i < a.length; i += 2) {
            if (h.map.put(Key.wrap(a[i]), a[i + 1]) == null) {
                added++;
            }
        }
        ctx.ks.modified();
        if (name.equals("hmset")) {
            c.out.raw(Resp.OK);
        } else {
            c.out.integer(added);
        }
    }

    private static void hsetnx(Context ctx, Client c, byte[][] a) {
        Key key = Args.key(a[1]);
        HashValue h = getHash(ctx, key);
        Key field = Key.wrap(a[2]);
        if (h != null && h.map.containsKey(field)) {
            c.out.integer(0);
            return;
        }
        getOrCreate(ctx, key).map.put(field, a[3]);
        ctx.ks.modified();
        c.out.integer(1);
    }

    private static void hget(Context ctx, Client c, byte[][] a) {
        HashValue h = getHash(ctx, Args.key(a[1]));
        c.out.bulk(h == null ? null : h.map.get(Key.wrap(a[2])));
    }

    private static void hmget(Context ctx, Client c, byte[][] a) {
        HashValue h = getHash(ctx, Args.key(a[1]));
        c.out.arrayHeader(a.length - 2);
        for (int i = 2; i < a.length; i++) {
            c.out.bulk(h == null ? null : h.map.get(Key.wrap(a[i])));
        }
    }

    private static void hdel(Context ctx, Client c, byte[][] a) {
        Key key = Args.key(a[1]);
        HashValue h = getHash(ctx, key);
        long removed = 0;
        if (h != null) {
            for (int i = 2; i < a.length; i++) {
                if (h.map.remove(Key.wrap(a[i])) != null) {
                    removed++;
                }
            }
            if (removed > 0) {
                ctx.ks.modified();
                ctx.ks.removeIfEmpty(key, h);
            }
        }
        c.out.integer(removed);
    }

    private static void dump(Context ctx, Client c, byte[][] a, boolean fields, boolean values) {
        HashValue h = getHash(ctx, Args.key(a[1]));
        if (h == null) {
            c.out.raw(Resp.EMPTY_ARRAY);
            return;
        }
        c.out.arrayHeader(h.map.size() * ((fields ? 1 : 0) + (values ? 1 : 0)));
        for (Map.Entry<Key, byte[]> e : h.map.entrySet()) {
            if (fields) {
                c.out.bulk(e.getKey().bytes());
            }
            if (values) {
                c.out.bulk(e.getValue());
            }
        }
    }

    private static void hlen(Context ctx, Client c, byte[][] a) {
        HashValue h = getHash(ctx, Args.key(a[1]));
        c.out.integer(h == null ? 0 : h.map.size());
    }

    private static void hexists(Context ctx, Client c, byte[][] a) {
        HashValue h = getHash(ctx, Args.key(a[1]));
        c.out.integer(h != null && h.map.containsKey(Key.wrap(a[2])) ? 1 : 0);
    }

    private static void hstrlen(Context ctx, Client c, byte[][] a) {
        HashValue h = getHash(ctx, Args.key(a[1]));
        byte[] v = h == null ? null : h.map.get(Key.wrap(a[2]));
        c.out.integer(v == null ? 0 : v.length);
    }

    private static void hincrby(Context ctx, Client c, byte[][] a) {
        long by = Args.parseLong(a[3]);
        Key key = Args.key(a[1]);
        HashValue h = getHash(ctx, key);
        Key field = Key.wrap(a[2]);
        byte[] curBytes = h == null ? null : h.map.get(field);
        long cur = 0;
        if (curBytes != null) {
            try {
                cur = Bytes.parseLong(curBytes);
            } catch (Bytes.NotANumber e) {
                throw new CommandException("ERR hash value is not an integer");
            }
        }
        if ((by < 0 && cur < 0 && by < Long.MIN_VALUE - cur) || (by > 0 && cur > 0 && by > Long.MAX_VALUE - cur)) {
            throw CommandException.OVERFLOW;
        }
        long next = cur + by;
        getOrCreate(ctx, key).map.put(field, Bytes.ascii(next));
        ctx.ks.modified();
        c.out.integer(next);
    }
}
