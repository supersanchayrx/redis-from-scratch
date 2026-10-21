package io.github.supersanchayrx.redis.persist;

import io.github.supersanchayrx.redis.Config;
import io.github.supersanchayrx.redis.FakeClock;
import io.github.supersanchayrx.redis.Harness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static io.github.supersanchayrx.redis.Harness.bulk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AofTest {

    private static final long T0 = 1_800_000_000_000L;

    @TempDir
    Path dir;

    private Config config() {
        Config c = new Config();
        c.appendonly = true;
        c.appendfsync = Config.Fsync.ALWAYS;
        c.dir = dir;
        return c;
    }

    /** A server with its AOF open, as after startup (without binding a port). */
    private Harness open(FakeClock clock) throws Exception {
        Harness h = new Harness(config(), clock);
        AofLoader.load(h.ctx.aof.path(), h.ctx);
        h.ctx.aof.open();
        return h;
    }

    private String file() throws Exception {
        return Files.readString(dir.resolve(Aof.FILE_NAME), StandardCharsets.ISO_8859_1);
    }

    private static String cmd(String... argv) {
        return Harness.bulks(argv);
    }

    @Test
    void effectFormsAreLogged() throws Exception {
        FakeClock clock = new FakeClock(T0);
        Harness h = open(clock);
        h.run("SET a v EX 10");
        h.run("SET b v PX 2500");
        h.run("SET c v");
        h.run("EXPIRE c 100");
        h.run("PEXPIRE c 5000");
        h.run("SET d v");
        h.run("EXPIRE d 0");
        h.run("SETEX e 7 v");
        h.ctx.aof.flush();
        String expected = cmd("SET", "a", "v", "PXAT", Long.toString(T0 + 10_000))
                + cmd("SET", "b", "v", "PXAT", Long.toString(T0 + 2500))
                + cmd("SET", "c", "v")
                + cmd("PEXPIREAT", "c", Long.toString(T0 + 100_000))
                + cmd("PEXPIREAT", "c", Long.toString(T0 + 5000))
                + cmd("SET", "d", "v")
                + cmd("DEL", "d")
                + cmd("SET", "e", "v", "PXAT", Long.toString(T0 + 7000));
        assertEquals(expected, file());
    }

    @Test
    void readsFailuresAndNoOpsAreNotLogged() throws Exception {
        Harness h = open(new FakeClock(T0));
        h.run("GET a");
        h.run("PUBLISH ch msg");
        h.run("CONFIG GET save");
        h.run("DEL nosuch");
        h.run("SET a v EX 0");     // error
        h.run("LPUSH a x");
        h.run("INCR a");           // WRONGTYPE
        h.run("SET b 1 NX");
        h.run("SET b 2 NX");       // didn't set
        h.ctx.aof.flush();
        assertEquals(cmd("LPUSH", "a", "x") + cmd("SET", "b", "1", "NX"), file());
    }

    @Test
    void expiryIsLoggedAsDel() throws Exception {
        FakeClock clock = new FakeClock(T0);
        Harness h = open(clock);
        h.run("SET a v PX 100");
        clock.advanceMillis(200);
        h.run("GET a");
        h.ctx.aof.flush();
        assertEquals(cmd("SET", "a", "v", "PXAT", Long.toString(T0 + 100)) + cmd("DEL", "a"), file());
    }

    @Test
    void replayRestoresEveryType() throws Exception {
        FakeClock clock = new FakeClock(T0);
        Harness h = open(clock);
        List<String> writes = List.of(
                "SET s hello", "SET counter 10", "INCRBY counter 5", "APPEND s _world",
                "RPUSH l a b c", "LPUSH l z", "LPOP l", "LSET l 0 A",
                "HSET h f1 v1 f2 v2", "HDEL h f1", "HINCRBY h n 3",
                "ZADD z 1 a 2 b 3 c 1.5 d", "ZINCRBY z 10 a", "ZREM z b",
                "SET t v EX 1000", "SET gone v", "DEL gone", "MSET m1 x m2 y", "RENAME m2 m3");
        for (String w : writes) {
            h.run(w);
        }
        List<String> reads = List.of("GET s", "GET counter", "LRANGE l 0 -1", "HGETALL h",
                "ZRANGE z 0 -1 WITHSCORES", "PTTL t", "EXISTS gone", "MGET m1 m2 m3", "DBSIZE");
        List<String> before = h.runAll(reads.toArray(new String[0]));
        h.ctx.aof.close();

        Harness restarted = open(clock);
        assertEquals(before, restarted.runAll(reads.toArray(new String[0])));
    }

    @Test
    void ttlKeepsCountingWhileTheServerIsDown() throws Exception {
        FakeClock clock = new FakeClock(T0);
        Harness h = open(clock);
        h.run("SET short v EX 10");
        h.run("SET long v EX 100");
        h.ctx.aof.close();

        clock.advanceMillis(30_000); // down for 30 s
        Harness restarted = open(clock);
        assertEquals("$-1\r\n", restarted.run("GET short"), "absolute times: the 10 s key is gone");
        assertEquals(":70\r\n", restarted.run("TTL long"));
    }

    @Test
    void nothingExpiresWhileLoading() throws Exception {
        // INCR ran while k was alive. Replayed after k's time has passed, it must not create a fresh k = 1.
        FakeClock clock = new FakeClock(T0);
        Harness h = open(clock);
        h.run("SET k 1 EX 10");
        clock.advanceMillis(1000);
        h.run("INCR k");
        h.ctx.aof.close();

        clock.advanceMillis(60_000);
        Harness restarted = open(clock);
        assertEquals("$-1\r\n", restarted.run("GET k"));
        assertEquals(":0\r\n", restarted.run("EXISTS k"));
    }

    @Test
    void truncatedTailIsRepaired() throws Exception {
        FakeClock clock = new FakeClock(T0);
        Harness h = open(clock);
        for (int i = 0; i < 100; i++) {
            h.run("SET key" + i + " value" + i);
        }
        h.ctx.aof.close();
        Path f = dir.resolve(Aof.FILE_NAME);
        byte[] whole = Files.readAllBytes(f);
        Files.write(f, Arrays.copyOf(whole, whole.length - 7)); // a crash mid-write of the last command

        Harness restarted = new Harness(config(), clock);
        AofLoader.Result r = AofLoader.load(f, restarted.ctx);
        assertTrue(r.truncated());
        assertEquals(99, r.commands());
        long lastCommandStart = whole.length - cmd("SET", "key99", "value99").length();
        assertEquals(lastCommandStart, r.truncatedTo());
        assertEquals(lastCommandStart, Files.size(f));
        assertEquals(bulk("value98"), restarted.run("GET key98"));
        assertEquals("$-1\r\n", restarted.run("GET key99"));

        // Appending continues after the repaired end, and the next load is clean.
        restarted.ctx.aof.open();
        restarted.run("SET after repair");
        restarted.ctx.aof.close();
        Harness again = new Harness(config(), clock);
        AofLoader.Result r2 = AofLoader.load(f, again.ctx);
        assertFalse(r2.truncated());
        assertEquals(100, r2.commands());
        assertEquals(bulk("repair"), again.run("GET after"));
    }

    @Test
    void garbageInTheMiddleRefusesToStart() throws Exception {
        Path f = dir.resolve(Aof.FILE_NAME);
        String good = cmd("SET", "a", "1") + cmd("SET", "b", "2");
        Files.writeString(f, good + "X" + cmd("SET", "c", "3"), StandardCharsets.ISO_8859_1);
        Harness h = new Harness(config(), new FakeClock(T0));
        AofCorruptException e = assertThrows(AofCorruptException.class, () -> AofLoader.load(f, h.ctx));
        assertTrue(e.getMessage().contains("offset " + good.length()), e.getMessage());
    }

    @Test
    void unknownCommandRefusesToStart() throws Exception {
        Path f = dir.resolve(Aof.FILE_NAME);
        Files.writeString(f, cmd("SET", "a", "1") + cmd("FROBNICATE", "x"), StandardCharsets.ISO_8859_1);
        Harness h = new Harness(config(), new FakeClock(T0));
        AofCorruptException e = assertThrows(AofCorruptException.class, () -> AofLoader.load(f, h.ctx));
        assertTrue(e.getMessage().contains("FROBNICATE"), e.getMessage());
    }

    @Test
    void missingFileStartsEmpty() throws Exception {
        Harness h = new Harness(config(), new FakeClock(T0));
        AofLoader.Result r = AofLoader.load(dir.resolve(Aof.FILE_NAME), h.ctx);
        assertEquals(0, r.commands());
        assertEquals(0, h.ctx.ks.size());
    }

    @Test
    void rewriteCompactsAndReplaysToTheSameKeyspace() throws Exception {
        FakeClock clock = new FakeClock(T0);
        Harness h = open(clock);
        for (int i = 0; i < 1000; i++) {
            h.run("INCR counter");
        }
        for (int i = 0; i < 200; i++) {
            h.run("RPUSH list item" + i);
            h.run("HSET hash f" + i + " v" + i);
            h.run("ZADD zset " + (i * 0.1) + " m" + i);
        }
        h.run("ZADD zset inf top -inf bottom");
        h.run("SET ttl v EX 500");
        h.run("SET dead v PX 1");
        clock.advanceMillis(10); // "dead" has expired but nobody has read it
        h.ctx.aof.flush();
        long before = Files.size(dir.resolve(Aof.FILE_NAME));
        String reply = h.run("BGREWRITEAOF");
        assertTrue(reply.startsWith("+Append only file rewritten (5 keys"), reply);
        long after = Files.size(dir.resolve(Aof.FILE_NAME));
        assertTrue(after < before / 3, before + " -> " + after);

        // Writes after the rewrite go to the new file.
        h.run("INCR counter");
        List<String> reads = List.of("GET counter", "LRANGE list 0 -1", "HGETALL hash", "ZRANGE zset 0 -1 WITHSCORES",
                "PTTL ttl", "EXISTS dead", "DBSIZE");
        List<String> expected = h.runAll(reads.toArray(new String[0]));
        h.ctx.aof.close();
        Harness restarted = open(clock);
        assertEquals(expected, restarted.runAll(reads.toArray(new String[0])));
        assertEquals(bulk("1001"), restarted.run("GET counter"));
    }

    @Test
    void everysecFsyncsOnTheBackgroundThread() throws Exception {
        Config c = config();
        c.appendfsync = Config.Fsync.EVERYSEC;
        Harness h = new Harness(c, new FakeClock(T0));
        h.ctx.aof.open();
        assertTrue(h.ctx.aof.backgroundFsync());
        h.run("SET a 1");
        h.ctx.aof.flush();
        assertEquals(0, h.ctx.aof.fsyncs(), "everysec doesn't fsync on flush");
        h.ctx.aof.fsyncIfNeeded();
        long deadline = System.currentTimeMillis() + 5000;
        while (h.ctx.aof.fsyncs() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(1, h.ctx.aof.fsyncs());
        h.ctx.aof.fsyncIfNeeded(); // nothing new written: no second fsync
        Thread.sleep(50);
        assertEquals(1, h.ctx.aof.fsyncs());
        h.ctx.aof.close();
        assertEquals(cmd("SET", "a", "1"), file());
    }

    @Test
    void rewriteNeedsTheAofOn() {
        Harness h = new Harness();
        assertTrue(h.run("BGREWRITEAOF").startsWith("-ERR AOF is off"));
    }

    @Test
    void groupCommitOneFsyncPerFlush() throws Exception {
        Harness h = open(new FakeClock(T0));
        for (int i = 0; i < 50; i++) {
            h.run("SET k" + i + " v");
        }
        h.ctx.aof.flush();
        assertEquals(1, h.ctx.aof.fsyncs(), "50 writes in one iteration cost one fsync under always");
    }
}
