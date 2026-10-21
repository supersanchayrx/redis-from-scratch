package io.github.supersanchayrx.redis.commands;

import io.github.supersanchayrx.redis.ds.LinkedList;
import io.github.supersanchayrx.redis.protocol.Resp;
import io.github.supersanchayrx.redis.server.Client;
import io.github.supersanchayrx.redis.store.Key;
import io.github.supersanchayrx.redis.store.ListValue;

import java.util.Arrays;

import static io.github.supersanchayrx.redis.commands.CommandSpec.FAST;
import static io.github.supersanchayrx.redis.commands.CommandSpec.READONLY;
import static io.github.supersanchayrx.redis.commands.CommandSpec.WRITE;

/** LPUSH, RPUSH, LPUSHX, RPUSHX, LPOP, RPOP, LLEN, LRANGE, LINDEX, LSET, LREM, LTRIM. */
final class ListCommands {

    private ListCommands() {
    }

    static void register(CommandTable t) {
        t.add("LPUSH", -3, WRITE | FAST, (ctx, c, a) -> push(ctx, c, a, true, false));
        t.add("RPUSH", -3, WRITE | FAST, (ctx, c, a) -> push(ctx, c, a, false, false));
        t.add("LPUSHX", -3, WRITE | FAST, (ctx, c, a) -> push(ctx, c, a, true, true));
        t.add("RPUSHX", -3, WRITE | FAST, (ctx, c, a) -> push(ctx, c, a, false, true));
        t.add("LPOP", -2, WRITE | FAST, (ctx, c, a) -> pop(ctx, c, a, true));
        t.add("RPOP", -2, WRITE | FAST, (ctx, c, a) -> pop(ctx, c, a, false));
        t.add("LLEN", 2, READONLY | FAST, ListCommands::llen);
        t.add("LRANGE", 4, READONLY, ListCommands::lrange);
        t.add("LINDEX", 3, READONLY, ListCommands::lindex);
        t.add("LSET", 4, WRITE, ListCommands::lset);
        t.add("LREM", 4, WRITE, ListCommands::lrem);
        t.add("LTRIM", 4, WRITE, ListCommands::ltrim);
    }

    private static ListValue getList(Context ctx, Key k) {
        return ctx.ks.get(k, ListValue.class);
    }

    /** LPUSH a b c leaves c b a: each element is pushed in turn. Replies with the new length. */
    private static void push(Context ctx, Client c, byte[][] a, boolean left, boolean onlyIfExists) {
        Key key = Args.key(a[1]);
        ListValue l = getList(ctx, key);
        if (l == null) {
            if (onlyIfExists) {
                c.out.integer(0);
                return;
            }
            l = new ListValue();
            ctx.ks.add(key, l);
        }
        for (int i = 2; i < a.length; i++) {
            if (left) {
                l.list.pushFront(a[i]);
            } else {
                l.list.pushBack(a[i]);
            }
        }
        ctx.ks.modified();
        c.out.integer(l.list.size());
    }

    private static void pop(Context ctx, Client c, byte[][] a, boolean left) {
        if (a.length > 3) {
            throw CommandException.wrongArgs(left ? "lpop" : "rpop");
        }
        boolean hasCount = a.length == 3;
        long count = hasCount ? Args.parsePositive(a[2]) : 1;
        Key key = Args.key(a[1]);
        ListValue l = getList(ctx, key);
        if (l == null) {
            if (hasCount) {
                c.out.nullArray();
            } else {
                c.out.nullBulk();
            }
            return;
        }
        if (!hasCount) {
            c.out.bulk(left ? l.list.popFront() : l.list.popBack());
        } else {
            int n = (int) Math.min(count, l.list.size());
            c.out.arrayHeader(n);
            for (int i = 0; i < n; i++) {
                c.out.bulk(left ? l.list.popFront() : l.list.popBack());
            }
        }
        ctx.ks.modified();
        ctx.ks.removeIfEmpty(key, l);
    }

    private static void llen(Context ctx, Client c, byte[][] a) {
        ListValue l = getList(ctx, Args.key(a[1]));
        c.out.integer(l == null ? 0 : l.list.size());
    }

    private static void lrange(Context ctx, Client c, byte[][] a) {
        long start = Args.parseLong(a[2]);
        long stop = Args.parseLong(a[3]);
        ListValue l = getList(ctx, Args.key(a[1]));
        if (l == null) {
            c.out.raw(Resp.EMPTY_ARRAY);
            return;
        }
        long[] r = Args.clampRange(start, stop, l.list.size());
        if (r == null) {
            c.out.raw(Resp.EMPTY_ARRAY);
            return;
        }
        int from = (int) r[0];
        int n = (int) (r[1] - r[0] + 1);
        c.out.arrayHeader(n);
        // One walk to the start (from the nearer end), then along the links.
        LinkedList.Node<byte[]> node = l.list.nodeAt(from);
        for (int i = 0; i < n; i++) {
            c.out.bulk(node.value());
            node = node.next();
        }
    }

    private static int resolveIndex(long index, int size) {
        if (index < 0) {
            index += size;
        }
        return (index < 0 || index >= size) ? -1 : (int) index;
    }

    private static void lindex(Context ctx, Client c, byte[][] a) {
        long index = Args.parseLong(a[2]);
        ListValue l = getList(ctx, Args.key(a[1]));
        if (l == null) {
            c.out.nullBulk();
            return;
        }
        int i = resolveIndex(index, l.list.size());
        c.out.bulk(i < 0 ? null : l.list.get(i));
    }

    private static void lset(Context ctx, Client c, byte[][] a) {
        long index = Args.parseLong(a[2]);
        ListValue l = getList(ctx, Args.key(a[1]));
        if (l == null) {
            throw CommandException.NO_SUCH_KEY;
        }
        int i = resolveIndex(index, l.list.size());
        if (i < 0) {
            throw CommandException.INDEX_OUT_OF_RANGE;
        }
        l.list.set(l.list.nodeAt(i), a[3]);
        ctx.ks.modified();
        c.out.raw(Resp.OK);
    }

    private static void lrem(Context ctx, Client c, byte[][] a) {
        long count = Args.parseLong(a[2]);
        Key key = Args.key(a[1]);
        ListValue l = getList(ctx, key);
        if (l == null) {
            c.out.integer(0);
            return;
        }
        byte[] target = a[3];
        int removed = l.list.removeMatching(v -> Arrays.equals(v, target), count);
        if (removed > 0) {
            ctx.ks.modified();
            ctx.ks.removeIfEmpty(key, l);
        }
        c.out.integer(removed);
    }

    private static void ltrim(Context ctx, Client c, byte[][] a) {
        long start = Args.parseLong(a[2]);
        long stop = Args.parseLong(a[3]);
        Key key = Args.key(a[1]);
        ListValue l = getList(ctx, key);
        if (l == null) {
            c.out.raw(Resp.OK);
            return;
        }
        long[] r = Args.clampRange(start, stop, l.list.size());
        if (r == null) {
            l.list.clear();
        } else {
            l.list.trim((int) r[0], (int) r[1]);
        }
        ctx.ks.modified();
        ctx.ks.removeIfEmpty(key, l);
        c.out.raw(Resp.OK);
    }
}
