package io.github.supersanchayrx.redis.commands;

import io.github.supersanchayrx.redis.ds.SkipList;
import io.github.supersanchayrx.redis.protocol.Resp;
import io.github.supersanchayrx.redis.server.Client;
import io.github.supersanchayrx.redis.store.Key;
import io.github.supersanchayrx.redis.store.ZSetValue;
import io.github.supersanchayrx.redis.util.Bytes;

import java.util.ArrayList;
import java.util.List;

import static io.github.supersanchayrx.redis.commands.CommandSpec.FAST;
import static io.github.supersanchayrx.redis.commands.CommandSpec.READONLY;
import static io.github.supersanchayrx.redis.commands.CommandSpec.WRITE;

/**
 * ZADD, ZINCRBY, ZREM, ZSCORE, ZMSCORE, ZCARD, ZRANK, ZREVRANK, ZCOUNT, ZRANGE (by rank or score, REV,
 * LIMIT, WITHSCORES), ZREVRANGE, ZRANGEBYSCORE, ZREVRANGEBYSCORE, ZPOPMIN, ZPOPMAX.
 */
final class ZSetCommands {

    private ZSetCommands() {
    }

    static void register(CommandTable t) {
        t.add("ZADD", -4, WRITE | FAST, ZSetCommands::zadd);
        t.add("ZINCRBY", 4, WRITE | FAST, ZSetCommands::zincrby);
        t.add("ZREM", -3, WRITE | FAST, ZSetCommands::zrem);
        t.add("ZSCORE", 3, READONLY | FAST, ZSetCommands::zscore);
        t.add("ZMSCORE", -3, READONLY | FAST, ZSetCommands::zmscore);
        t.add("ZCARD", 2, READONLY | FAST, ZSetCommands::zcard);
        t.add("ZRANK", -3, READONLY | FAST, (ctx, c, a) -> zrank(ctx, c, a, false));
        t.add("ZREVRANK", -3, READONLY | FAST, (ctx, c, a) -> zrank(ctx, c, a, true));
        t.add("ZCOUNT", 4, READONLY | FAST, ZSetCommands::zcount);
        t.add("ZRANGE", -4, READONLY, (ctx, c, a) -> zrange(ctx, c, a, RangeType.AUTO, false, "zrange"));
        t.add("ZREVRANGE", -4, READONLY, (ctx, c, a) -> zrange(ctx, c, a, RangeType.RANK, true, "zrevrange"));
        t.add("ZRANGEBYSCORE", -4, READONLY,
                (ctx, c, a) -> zrange(ctx, c, a, RangeType.SCORE, false, "zrangebyscore"));
        t.add("ZREVRANGEBYSCORE", -4, READONLY,
                (ctx, c, a) -> zrange(ctx, c, a, RangeType.SCORE, true, "zrevrangebyscore"));
        t.add("ZPOPMIN", -2, WRITE | FAST, (ctx, c, a) -> zpop(ctx, c, a, false));
        t.add("ZPOPMAX", -2, WRITE | FAST, (ctx, c, a) -> zpop(ctx, c, a, true));
    }

    private static ZSetValue getZSet(Context ctx, Key k) {
        return ctx.ks.get(k, ZSetValue.class);
    }

    private static void score(Client c, double d) {
        c.out.bulk(Bytes.formatDoubleBytes(d));
    }

    /** ZADD key [NX | XX] [GT | LT] [CH] [INCR] score member [score member ...] */
    private static void zadd(Context ctx, Client c, byte[][] a) {
        boolean nx = false;
        boolean xx = false;
        boolean gt = false;
        boolean lt = false;
        boolean ch = false;
        boolean incr = false;
        int i = 2;
        for (; i < a.length; i++) {
            byte[] o = a[i];
            if (Args.eq(o, "NX")) {
                nx = true;
            } else if (Args.eq(o, "XX")) {
                xx = true;
            } else if (Args.eq(o, "GT")) {
                gt = true;
            } else if (Args.eq(o, "LT")) {
                lt = true;
            } else if (Args.eq(o, "CH")) {
                ch = true;
            } else if (Args.eq(o, "INCR")) {
                incr = true;
            } else {
                break;
            }
        }
        int elements = a.length - i;
        if (elements == 0 || elements % 2 != 0) {
            throw CommandException.SYNTAX;
        }
        elements /= 2;
        if (nx && xx) {
            throw new CommandException("ERR XX and NX options at the same time are not compatible");
        }
        if ((gt && nx) || (lt && nx) || (gt && lt)) {
            throw new CommandException("ERR GT, LT, and/or NX options at the same time are not compatible");
        }
        if (incr && elements > 1) {
            throw new CommandException("ERR INCR option supports a single increment-element pair");
        }
        // Parse every score before changing anything: one bad score means no change at all.
        double[] scores = new double[elements];
        for (int j = 0; j < elements; j++) {
            scores[j] = Args.parseDouble(a[i + 2 * j]);
        }

        Key key = Args.key(a[1]);
        ZSetValue z = getZSet(ctx, key);
        if (z == null) {
            if (xx) {
                if (incr) {
                    c.out.nullBulk();
                } else {
                    c.out.integer(0);
                }
                return;
            }
            z = new ZSetValue();
            ctx.ks.add(key, z);
        }

        long added = 0;
        long updated = 0;
        boolean processed = false;
        double lastScore = 0;
        for (int j = 0; j < elements; j++) {
            Key member = Key.wrap(a[i + 2 * j + 1]);
            double s = scores[j];
            Double cur = z.score(member);
            if (cur != null) {
                if (nx) {
                    continue;
                }
                double next = incr ? cur + s : s;
                if (Double.isNaN(next)) {
                    ctx.ks.removeIfEmpty(key, z);
                    throw new CommandException("ERR resulting score is not a number (NaN)");
                }
                if ((lt && next >= cur) || (gt && next <= cur)) {
                    continue;
                }
                if (next != cur) {
                    z.put(member, next);
                    updated++;
                }
                lastScore = next;
                processed = true;
            } else {
                if (xx) {
                    continue;
                }
                z.put(member, s);
                added++;
                lastScore = s;
                processed = true;
            }
        }
        if (added + updated > 0) {
            ctx.ks.modified();
        }
        ctx.ks.removeIfEmpty(key, z);
        if (incr) {
            if (processed) {
                score(c, lastScore);
            } else {
                c.out.nullBulk();
            }
        } else {
            c.out.integer(ch ? added + updated : added);
        }
    }

    private static void zincrby(Context ctx, Client c, byte[][] a) {
        double by = Args.parseDouble(a[2]);
        Key key = Args.key(a[1]);
        ZSetValue z = getZSet(ctx, key);
        Key member = Key.wrap(a[3]);
        Double cur = z == null ? null : z.score(member);
        double next = cur == null ? by : cur + by;
        if (Double.isNaN(next)) {
            throw new CommandException("ERR resulting score is not a number (NaN)");
        }
        if (z == null) {
            z = new ZSetValue();
            ctx.ks.add(key, z);
        }
        z.put(member, next);
        ctx.ks.modified();
        score(c, next);
    }

    private static void zrem(Context ctx, Client c, byte[][] a) {
        Key key = Args.key(a[1]);
        ZSetValue z = getZSet(ctx, key);
        long removed = 0;
        if (z != null) {
            for (int i = 2; i < a.length; i++) {
                if (z.remove(Key.wrap(a[i]))) {
                    removed++;
                }
            }
            if (removed > 0) {
                ctx.ks.modified();
                ctx.ks.removeIfEmpty(key, z);
            }
        }
        c.out.integer(removed);
    }

    private static void zscore(Context ctx, Client c, byte[][] a) {
        ZSetValue z = getZSet(ctx, Args.key(a[1]));
        Double s = z == null ? null : z.score(Key.wrap(a[2]));
        if (s == null) {
            c.out.nullBulk();
        } else {
            score(c, s);
        }
    }

    private static void zmscore(Context ctx, Client c, byte[][] a) {
        ZSetValue z = getZSet(ctx, Args.key(a[1]));
        c.out.arrayHeader(a.length - 2);
        for (int i = 2; i < a.length; i++) {
            Double s = z == null ? null : z.score(Key.wrap(a[i]));
            if (s == null) {
                c.out.nullBulk();
            } else {
                score(c, s);
            }
        }
    }

    private static void zcard(Context ctx, Client c, byte[][] a) {
        ZSetValue z = getZSet(ctx, Args.key(a[1]));
        c.out.integer(z == null ? 0 : z.size());
    }

    /** ZRANK key member [WITHSCORE]: 0-based (the skip list's ranks are 1-based). */
    private static void zrank(Context ctx, Client c, byte[][] a, boolean reverse) {
        if (a.length > 4) {
            throw CommandException.wrongArgs(reverse ? "zrevrank" : "zrank");
        }
        boolean withScore = false;
        if (a.length == 4) {
            if (!Args.eq(a[3], "WITHSCORE")) {
                throw CommandException.SYNTAX;
            }
            withScore = true;
        }
        ZSetValue z = getZSet(ctx, Args.key(a[1]));
        Key member = Key.wrap(a[2]);
        long rank = z == null ? -1 : z.rank(member);
        if (rank < 0) {
            if (withScore) {
                c.out.nullArray();
            } else {
                c.out.nullBulk();
            }
            return;
        }
        if (reverse) {
            rank = z.size() - 1 - rank;
        }
        if (withScore) {
            c.out.arrayHeader(2).integer(rank);
            score(c, z.score(member));
        } else {
            c.out.integer(rank);
        }
    }

    private static void zcount(Context ctx, Client c, byte[][] a) {
        SkipList.Range range = Args.parseScoreRange(a[2], a[3]);
        ZSetValue z = getZSet(ctx, Args.key(a[1]));
        if (z == null) {
            c.out.integer(0);
            return;
        }
        SkipList.Node first = z.list.firstInRange(range);
        if (first == null) {
            c.out.integer(0);
            return;
        }
        SkipList.Node last = z.list.lastInRange(range);
        long count = z.list.rank(last.score(), last.member()) - z.list.rank(first.score(), first.member()) + 1;
        c.out.integer(count);
    }

    private enum RangeType { AUTO, RANK, SCORE }

    /**
     * The shared range command, after Redis's zrangeGenericCommand. ZRANGE takes BYSCORE / REV / LIMIT /
     * WITHSCORES; the older forms fix the range type and direction and reject the options that change them.
     */
    private static void zrange(Context ctx, Client c, byte[][] a, RangeType type, boolean reverse, String name) {
        boolean withScores = false;
        long offset = 0;
        long limit = -1;
        boolean hasLimit = false;
        boolean directionFixed = type != RangeType.AUTO;
        for (int j = 4; j < a.length; j++) {
            int left = a.length - j - 1;
            if (Args.eq(a[j], "WITHSCORES")) {
                withScores = true;
            } else if (Args.eq(a[j], "LIMIT") && left >= 2) {
                offset = Args.parseLong(a[j + 1]);
                limit = Args.parseLong(a[j + 2]);
                hasLimit = true;
                j += 2;
            } else if (!directionFixed && Args.eq(a[j], "REV")) {
                reverse = true;
            } else if (type == RangeType.AUTO && Args.eq(a[j], "BYSCORE")) {
                type = RangeType.SCORE;
            } else if (type == RangeType.AUTO && Args.eq(a[j], "BYLEX")) {
                throw new CommandException("ERR BYLEX is not supported by this server");
            } else {
                throw CommandException.SYNTAX;
            }
        }
        if (type == RangeType.AUTO) {
            type = RangeType.RANK;
        }
        if (hasLimit && type == RangeType.RANK) {
            throw new CommandException("ERR syntax error, LIMIT is only supported in combination with either BYSCORE or BYLEX");
        }
        Key key = Args.key(a[1]);
        if (type == RangeType.RANK) {
            long start = Args.parseLong(a[2]);
            long stop = Args.parseLong(a[3]);
            ZSetValue z = getZSet(ctx, key);
            if (z == null) {
                c.out.raw(Resp.EMPTY_ARRAY);
                return;
            }
            long len = z.size();
            long[] r = Args.clampRange(start, stop, len);
            if (r == null) {
                c.out.raw(Resp.EMPTY_ARRAY);
                return;
            }
            int n = (int) (r[1] - r[0] + 1);
            c.out.arrayHeader(withScores ? n * 2 : n);
            // Find the first node by rank in O(log n), then walk level 0.
            SkipList.Node node = reverse ? z.list.nodeAtRank(len - r[0]) : z.list.nodeAtRank(r[0] + 1);
            for (int i = 0; i < n; i++) {
                emit(c, node, withScores);
                node = reverse ? node.prev() : node.next();
            }
            return;
        }
        // By score. With REV the arguments are max then min.
        SkipList.Range range = reverse ? Args.parseScoreRange(a[3], a[2]) : Args.parseScoreRange(a[2], a[3]);
        ZSetValue z = getZSet(ctx, key);
        if (z == null || offset < 0) {
            c.out.raw(Resp.EMPTY_ARRAY);
            return;
        }
        SkipList.Node node = reverse ? z.list.lastInRange(range) : z.list.firstInRange(range);
        while (node != null && offset > 0) {
            node = reverse ? node.prev() : node.next();
            offset--;
        }
        List<SkipList.Node> out = new ArrayList<>();
        while (node != null && limit != 0) {
            double s = node.score();
            if (reverse ? !aboveMin(range, s) : !belowMax(range, s)) {
                break;
            }
            out.add(node);
            node = reverse ? node.prev() : node.next();
            if (limit > 0) {
                limit--;
            }
        }
        c.out.arrayHeader(withScores ? out.size() * 2 : out.size());
        for (SkipList.Node n : out) {
            emit(c, n, withScores);
        }
    }

    private static boolean aboveMin(SkipList.Range r, double s) {
        return r.minExclusive() ? s > r.min() : s >= r.min();
    }

    private static boolean belowMax(SkipList.Range r, double s) {
        return r.maxExclusive() ? s < r.max() : s <= r.max();
    }

    private static void emit(Client c, SkipList.Node node, boolean withScores) {
        c.out.bulk(node.member().bytes());
        if (withScores) {
            score(c, node.score());
        }
    }

    /** ZPOPMIN / ZPOPMAX key [count]: a flat member, score array (RESP2). */
    private static void zpop(Context ctx, Client c, byte[][] a, boolean max) {
        if (a.length > 3) {
            throw CommandException.SYNTAX;
        }
        long count = a.length == 3 ? Args.parsePositive(a[2]) : 1;
        Key key = Args.key(a[1]);
        ZSetValue z = getZSet(ctx, key);
        if (z == null || count == 0) {
            c.out.raw(Resp.EMPTY_ARRAY);
            return;
        }
        int n = (int) Math.min(count, z.size());
        c.out.arrayHeader(n * 2);
        for (int i = 0; i < n; i++) {
            SkipList.Node node = max ? z.list.last() : z.list.first();
            Key member = node.member();
            double s = node.score();
            z.remove(member);
            c.out.bulk(member.bytes());
            score(c, s);
        }
        ctx.ks.modified();
        ctx.ks.removeIfEmpty(key, z);
    }
}
