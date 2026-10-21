package io.github.supersanchayrx.redis.commands;

import io.github.supersanchayrx.redis.protocol.Resp;
import io.github.supersanchayrx.redis.server.Client;
import io.github.supersanchayrx.redis.store.Key;
import io.github.supersanchayrx.redis.store.Value;
import io.github.supersanchayrx.redis.util.Glob;

import java.util.ArrayList;
import java.util.List;

import static io.github.supersanchayrx.redis.commands.CommandSpec.FAST;
import static io.github.supersanchayrx.redis.commands.CommandSpec.READONLY;
import static io.github.supersanchayrx.redis.commands.CommandSpec.WRITE;

/** DEL, UNLINK, EXISTS, TYPE, KEYS, RENAME. */
final class KeyCommands {

    private KeyCommands() {
    }

    static void register(CommandTable t) {
        t.add("DEL", -2, WRITE, KeyCommands::del);
        t.add("UNLINK", -2, WRITE | FAST, KeyCommands::del);
        t.add("EXISTS", -2, READONLY | FAST, KeyCommands::exists);
        t.add("TYPE", 2, READONLY | FAST, KeyCommands::type);
        t.add("KEYS", 2, READONLY, KeyCommands::keys);
        t.add("RENAME", 3, WRITE, KeyCommands::rename);
    }

    private static void del(Context ctx, Client c, byte[][] a) {
        long n = 0;
        for (int i = 1; i < a.length; i++) {
            if (ctx.ks.delete(Args.key(a[i]))) {
                n++;
            }
        }
        c.out.integer(n);
    }

    /** Counts repeats: EXISTS a a is 2 when a exists. */
    private static void exists(Context ctx, Client c, byte[][] a) {
        long n = 0;
        for (int i = 1; i < a.length; i++) {
            if (ctx.ks.exists(Args.key(a[i]))) {
                n++;
            }
        }
        c.out.integer(n);
    }

    private static void type(Context ctx, Client c, byte[][] a) {
        Value v = ctx.ks.get(Args.key(a[1]));
        c.out.simple(v == null ? "none" : v.typeName());
    }

    private static void keys(Context ctx, Client c, byte[][] a) {
        byte[] pattern = a[1];
        boolean all = Glob.isMatchAll(pattern);
        List<Key> out = new ArrayList<>();
        ctx.ks.forEachLiveKey(k -> {
            if (all || Glob.match(pattern, k.bytes())) {
                out.add(k);
            }
        });
        c.out.arrayHeader(out.size());
        for (Key k : out) {
            c.out.bulk(k.bytes());
        }
    }

    /** Moves the value and its TTL. RENAME onto itself is a no-op that still replies OK. */
    private static void rename(Context ctx, Client c, byte[][] a) {
        Key from = Args.key(a[1]);
        Key to = Args.key(a[2]);
        Value v = ctx.ks.get(from);
        if (v == null) {
            throw CommandException.NO_SUCH_KEY;
        }
        if (from.equals(to)) {
            c.out.raw(Resp.OK);
            return;
        }
        long expireAt = ctx.ks.expireAt(from);
        ctx.ks.delete(from);
        ctx.ks.set(to, v);
        if (expireAt >= 0) {
            ctx.ks.setExpire(to, expireAt);
        }
        c.out.raw(Resp.OK);
    }
}
