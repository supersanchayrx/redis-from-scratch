package io.github.supersanchayrx.redis.store;

import io.github.supersanchayrx.redis.Clock;

import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

/**
 * Active expiry: Redis's slow cycle from expire.c. Each run samples 20 random keys that have a TTL and
 * deletes the expired ones, and repeats while more than 10% of a sample was expired, within a time budget
 * of 25% of a timer tick.
 *
 * <p>Why it works: once a sample comes back 10% stale or less, roughly 10% or less of the volatile keys
 * are expired-but-present. And the budget means a million keys expiring at once can't freeze the server.
 */
public final class ExpiryCycle {

    /** ACTIVE_EXPIRE_CYCLE_KEYS_PER_LOOP. */
    public static final int KEYS_PER_LOOP = 20;
    /** ACTIVE_EXPIRE_CYCLE_ACCEPTABLE_STALE, in percent. */
    public static final int ACCEPTABLE_STALE = 10;
    /** ACTIVE_EXPIRE_CYCLE_SLOW_TIME_PERC: the share of each tick the cycle may use. */
    public static final int SLOW_TIME_PERC = 25;

    private final Keyspace ks;
    private final Clock clock;
    private final RandomGenerator rng;
    private final long budgetNanos;

    private long runs;
    private long longestRunNanos;
    private long timeLimitHits;

    public ExpiryCycle(Keyspace ks, Clock clock, int hz) {
        this(ks, clock, hz, new SplittableRandom());
    }

    public ExpiryCycle(Keyspace ks, Clock clock, int hz, RandomGenerator rng) {
        this.ks = ks;
        this.clock = clock;
        this.rng = rng;
        this.budgetNanos = 1_000_000_000L / hz * SLOW_TIME_PERC / 100;
    }

    /** One run of the cycle. Returns the number of keys it expired. */
    public int run() {
        long start = clock.nanos();
        ks.setTime(clock.millis());
        int expiredTotal = 0;
        while (true) {
            int volatileKeys = ks.volatileCount();
            if (volatileKeys == 0) {
                break;
            }
            int sample = Math.min(KEYS_PER_LOOP, volatileKeys);
            int expired = 0;
            for (int i = 0; i < sample; i++) {
                Key k = ks.randomVolatile(rng);
                if (k != null && ks.expireIfNeeded(k)) {
                    expired++;
                }
            }
            expiredTotal += expired;
            if (clock.nanos() - start > budgetNanos) {
                timeLimitHits++;
                break;
            }
            if (expired * 100 <= sample * ACCEPTABLE_STALE) {
                break;
            }
        }
        runs++;
        longestRunNanos = Math.max(longestRunNanos, clock.nanos() - start);
        return expiredTotal;
    }

    public long runs() {
        return runs;
    }

    public long longestRunNanos() {
        return longestRunNanos;
    }

    public long timeLimitHits() {
        return timeLimitHits;
    }

    public long budgetNanos() {
        return budgetNanos;
    }
}
