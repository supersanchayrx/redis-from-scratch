package io.github.supersanchayrx.redis.commands;

import io.github.supersanchayrx.redis.ds.SkipList;
import io.github.supersanchayrx.redis.store.Key;
import io.github.supersanchayrx.redis.util.Bytes;

/** Argument parsing shared by the handlers, with Redis's error replies. */
final class Args {

    private Args() {
    }

    static Key key(byte[] arg) {
        return Key.wrap(arg);
    }

    static long parseLong(byte[] arg) {
        try {
            return Bytes.parseLong(arg);
        } catch (Bytes.NotANumber e) {
            throw CommandException.NOT_INTEGER;
        }
    }

    /** A count that must be 0 or more (LPOP's and ZPOPMIN's count). */
    static long parsePositive(byte[] arg) {
        long v = parseLong(arg);
        if (v < 0) {
            throw CommandException.NOT_POSITIVE;
        }
        return v;
    }

    static double parseDouble(byte[] arg) {
        try {
            return Bytes.parseDouble(arg);
        } catch (Bytes.NotANumber e) {
            throw CommandException.NOT_FLOAT;
        }
    }

    /** A score bound for ZRANGEBYSCORE and friends: a number, "-inf"/"+inf", or "(" for exclusive. */
    static SkipList.Range parseScoreRange(byte[] min, byte[] max) {
        double lo;
        double hi;
        boolean loEx = min.length > 0 && min[0] == '(';
        boolean hiEx = max.length > 0 && max[0] == '(';
        try {
            lo = Bytes.parseDouble(loEx ? java.util.Arrays.copyOfRange(min, 1, min.length) : min);
            hi = Bytes.parseDouble(hiEx ? java.util.Arrays.copyOfRange(max, 1, max.length) : max);
        } catch (Bytes.NotANumber e) {
            throw CommandException.MIN_MAX_NOT_FLOAT;
        }
        return new SkipList.Range(lo, loEx, hi, hiEx);
    }

    /**
     * Resolves a [start, stop] index pair against a length the way LRANGE and ZRANGE do: negatives count
     * from the end, start clamps to 0, stop clamps to the last element. Returns null for an empty range.
     */
    static long[] clampRange(long start, long stop, long len) {
        if (start < 0) {
            start = len + start;
        }
        if (stop < 0) {
            stop = len + stop;
        }
        if (start < 0) {
            start = 0;
        }
        if (start > stop || start >= len) {
            return null;
        }
        if (stop >= len) {
            stop = len - 1;
        }
        return new long[] {start, stop};
    }

    static boolean eq(byte[] arg, String upper) {
        return Bytes.eq(arg, upper);
    }
}
