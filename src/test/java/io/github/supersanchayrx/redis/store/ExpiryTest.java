package io.github.supersanchayrx.redis.store;

import io.github.supersanchayrx.redis.Config;
import io.github.supersanchayrx.redis.FakeClock;
import io.github.supersanchayrx.redis.Harness;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TTL semantics and both expiry paths, with a fake clock: "an hour later" takes a millisecond. */
class ExpiryTest {

    @Test
    void ttlReplies() {
        Harness h = new Harness();
        assertEquals(":-2\r\n", h.run("TTL nosuch"));
        h.run("SET a 1");
        assertEquals(":-1\r\n", h.run("TTL a"));
        h.run("EXPIRE a 100");
        assertEquals(":100\r\n", h.run("TTL a"));
        assertEquals(":100000\r\n", h.run("PTTL a"));
        h.clock.advanceMillis(1500);
        // 98.5 s left rounds to 99, as in Redis.
        assertEquals(":99\r\n", h.run("TTL a"));
        assertEquals(":98500\r\n", h.run("PTTL a"));
        assertEquals(":1\r\n", h.run("PERSIST a"));
        assertEquals(":0\r\n", h.run("PERSIST a"));
        assertEquals(":-1\r\n", h.run("TTL a"));
    }

    @Test
    void keyDisappearsAfterItsTime() {
        Harness h = new Harness();
        h.run("SET a 1 PX 1000");
        h.clock.advanceMillis(1000);
        assertEquals("$1\r\n1\r\n", h.run("GET a"), "alive at exactly its expiry time (expired once now > time)");
        h.clock.advanceMillis(1);
        assertEquals("$-1\r\n", h.run("GET a"));
        assertEquals(0, h.ctx.ks.size(), "the lazy lookup deleted it");
    }

    @Test
    void nonPositiveTimeoutDeletes() {
        Harness h = new Harness();
        h.run("SET a 1");
        assertEquals(":1\r\n", h.run("EXPIRE a 0"));
        assertEquals(":0\r\n", h.run("EXISTS a"));
        h.run("SET b 1");
        assertEquals(":1\r\n", h.run("PEXPIRE b -5"));
        assertEquals(":0\r\n", h.run("EXISTS b"));
        assertEquals(":0\r\n", h.run("EXPIRE nosuch 10"));
    }

    @Test
    void whichCommandsKeepTheTtl() {
        Harness h = new Harness();
        h.run("SET n 1 EX 100");
        h.run("INCR n");
        assertEquals(":100\r\n", h.run("TTL n"), "INCR keeps the TTL");
        h.run("SET n 5");
        assertEquals(":-1\r\n", h.run("TTL n"), "SET clears it");
        h.run("SET n 5 EX 100");
        h.run("SET n 6 KEEPTTL");
        assertEquals(":100\r\n", h.run("TTL n"), "SET KEEPTTL keeps it");
        h.run("RPUSH l a");
        h.run("EXPIRE l 100");
        h.run("RPUSH l b");
        assertEquals(":100\r\n", h.run("TTL l"), "RPUSH keeps it");
        h.run("DEL l");
        h.run("RPUSH l a");
        assertEquals(":-1\r\n", h.run("TTL l"), "DEL clears it");
    }

    @Test
    void lazyDeleteOnEveryKindOfRead() {
        Harness h = new Harness();
        for (String k : new String[] {"a", "b", "c", "d"}) {
            h.run("SET " + k + " v PX 10");
        }
        h.clock.advanceMillis(11);
        assertEquals(":0\r\n", h.run("EXISTS a"));
        assertEquals("+none\r\n", h.run("TYPE b"));
        assertEquals("*1\r\n$-1\r\n", h.run("MGET c"));
        assertEquals("$-1\r\n", h.run("GET d"));
        assertEquals(0, h.ctx.ks.size());
        assertEquals(4, h.ctx.ks.expiredKeys());
    }

    @Test
    void activeCycleRemovesUnreadKeysAndKeepsLiveOnes() {
        FakeClock clock = new FakeClock(1_800_000_000_000L);
        Harness h = new Harness(new Config(), clock);
        for (int i = 0; i < 10_000; i++) {
            h.run("SET k" + i + " v PX " + (i % 2 == 0 ? 1000 : 1_000_000));
        }
        for (int i = 0; i < 1000; i++) {
            h.run("SET persistent" + i + " v");
        }
        clock.advanceMillis(2000);
        ExpiryCycle cycle = new ExpiryCycle(h.ctx.ks, clock, 10, new SplittableRandom(3));
        int runs = 0;
        while (h.ctx.ks.size() > 5000 + 1000 + 500 && runs < 10_000) {
            cycle.run();
            runs++;
        }
        // Once fewer than ~10% of samples are stale the cycle stops early, so a few may linger; keep going.
        for (int i = 0; i < 2000; i++) {
            cycle.run();
        }
        h.ctx.ks.setTime(clock.millis());
        int expiredLeft = 0;
        for (int i = 0; i < 10_000; i += 2) {
            if (h.ctx.ks.isExpired(Key.of("k" + i))) {
                expiredLeft++;
            }
        }
        assertTrue(expiredLeft < 100, expiredLeft + " expired keys still held");
        for (int i = 1; i < 10_000; i += 2) {
            assertTrue(h.ctx.ks.exists(Key.of("k" + i)), "live key k" + i + " was removed");
        }
        assertEquals(5000 + 1000 + expiredLeft, h.ctx.ks.size());
    }

    @Test
    void cycleStopsAtItsTimeBudget() {
        FakeClock clock = new FakeClock(1_800_000_000_000L);
        Harness h = new Harness(new Config(), clock);
        for (int i = 0; i < 100_000; i++) {
            h.run("SET k" + i + " v PX 10");
        }
        clock.advanceMillis(100);
        // Every clock read costs 1 ms, so the 25 ms budget is spent after ~25 sampling rounds.
        clock.nanosPerRead = 1_000_000;
        ExpiryCycle cycle = new ExpiryCycle(h.ctx.ks, clock, 10, new SplittableRandom(4));
        int expired = cycle.run();
        assertEquals(1, cycle.timeLimitHits());
        assertTrue(expired > 0 && expired <= 30 * ExpiryCycle.KEYS_PER_LOOP, "expired " + expired);
        assertTrue(h.ctx.ks.size() > 90_000, "one run must not clear everything at once");
    }

    @Test
    void expireOptions() {
        Harness h = new Harness();
        h.run("SET a 1");
        assertEquals(":0\r\n", h.run("EXPIRE a 100 XX"));
        assertEquals(":0\r\n", h.run("EXPIRE a 100 GT"), "no TTL counts as infinite");
        assertEquals(":1\r\n", h.run("EXPIRE a 100 LT"));
        assertEquals(":0\r\n", h.run("EXPIRE a 50 NX"));
        assertEquals(":1\r\n", h.run("EXPIRE a 200 GT"));
        assertEquals(":0\r\n", h.run("EXPIRE a 300 LT"));
        assertEquals(":200\r\n", h.run("TTL a"));
    }
}
