package io.github.supersanchayrx.redis.commands;

import io.github.supersanchayrx.redis.Harness;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static io.github.supersanchayrx.redis.Harness.bulk;
import static io.github.supersanchayrx.redis.Harness.bulks;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Replies for each command family. Expected bytes were captured from redis:8.10.2; bench/compat-commands.txt
 * holds the wider set that bench/RespDiff.java checks against a live real Redis.
 */
class CommandsTest {

    private static final String WRONGTYPE = "-WRONGTYPE Operation against a key holding the wrong kind of value\r\n";

    @Test
    void dispatchErrors() {
        Harness h = new Harness();
        assertEquals("-ERR unknown command 'foo', with args beginning with: 'a' 'b' \r\n", h.run("foo a b"));
        assertEquals("-ERR unknown command 'foo'\r\n", h.run("foo"));
        assertEquals("-ERR wrong number of arguments for 'get' command\r\n", h.run("GET"));
        assertEquals("-ERR wrong number of arguments for 'set' command\r\n", h.run("set a"));
        assertEquals("-ERR wrong number of arguments for 'mset' command\r\n", h.run("MSET a b c"));
        assertEquals("-ERR wrong number of arguments for 'hset' command\r\n", h.run("HSET h f"));
        assertEquals("+PONG\r\n", h.run("ping"), "names are case-insensitive");
    }

    @Test
    void strings() {
        Harness h = new Harness();
        assertEquals("+OK\r\n", h.run("SET a 1"));
        assertEquals(bulk("1"), h.run("GET a"));
        assertEquals("$-1\r\n", h.run("SET a 2 NX"));
        assertEquals("+OK\r\n", h.run("SET a 3 XX"));
        assertEquals("$-1\r\n", h.run("SET b 1 XX"));
        assertEquals("-ERR syntax error\r\n", h.run("SET a v EX"));
        assertEquals("-ERR syntax error\r\n", h.run("SET a v NX XX"));
        assertEquals("-ERR invalid expire time in 'set' command\r\n", h.run("SET a v EX 0"));
        assertEquals("-ERR value is not an integer or out of range\r\n", h.run("SET a v EX abc"));
        assertEquals(bulk("3"), h.run("SET a v GET"));
        assertEquals("+OK\r\n", h.run("MSET x a y b"));
        assertEquals(bulks("a", "b", null), h.run("MGET x y z"));
        assertEquals(":2\r\n", h.run("EXISTS x x"));
        assertEquals(":2\r\n", h.run("DEL x y z"));
        assertEquals("+string\r\n", h.run("TYPE a"));
        assertEquals("+none\r\n", h.run("TYPE nosuch"));
    }

    @Test
    void incrEdgeCases() {
        Harness h = new Harness();
        assertEquals(":1\r\n", h.run("INCR c"));
        assertEquals(":11\r\n", h.run("INCRBY c 10"));
        assertEquals(":6\r\n", h.run("DECRBY c 5"));
        h.run("SET big 9223372036854775807");
        assertEquals("-ERR increment or decrement would overflow\r\n", h.run("INCR big"));
        h.run("SET small -9223372036854775808");
        assertEquals("-ERR increment or decrement would overflow\r\n", h.run("DECR small"));
        assertEquals("-ERR decrement would overflow\r\n", h.run("DECRBY c -9223372036854775808"));
        for (String bad : new String[] {"hello", "-0", "01", "+1", "1.5"}) {
            h.run("SET n " + bad);
            assertEquals("-ERR value is not an integer or out of range\r\n", h.run("INCR n"), bad);
        }
        h.runArgs(b("SET"), b("sp"), b(" 1"));
        assertEquals("-ERR value is not an integer or out of range\r\n", h.run("INCR sp"));
    }

    @Test
    void wrongType() {
        Harness h = new Harness();
        h.run("LPUSH l x");
        h.run("SET s x");
        assertEquals(WRONGTYPE, h.run("GET l"));
        assertEquals(WRONGTYPE, h.run("LPUSH s x"));
        assertEquals(WRONGTYPE, h.run("HGET s f"));
        assertEquals(WRONGTYPE, h.run("ZADD s 1 a"));
        assertEquals(WRONGTYPE, h.run("INCR l"));
        assertEquals(bulks("x", null, null), h.run("MGET s l nosuch"), "MGET never raises WRONGTYPE");
    }

    @Test
    void binaryKeysRoundTrip() {
        Harness h = new Harness();
        byte[] key = {0, (byte) 0xff, '\r', '\n'};
        byte[] val = {(byte) 0x80, 0, 1};
        assertEquals("+OK\r\n", h.runArgs(b("SET"), key, val));
        String reply = h.runArgs(b("GET"), key);
        assertEquals("$3\r\n" + new String(val, StandardCharsets.ISO_8859_1) + "\r\n", reply);
        assertEquals(":1\r\n", h.runArgs(b("EXISTS"), key));
        assertEquals(1, h.ctx.ks.size());
    }

    @Test
    void lists() {
        Harness h = new Harness();
        assertEquals(":3\r\n", h.run("LPUSH l a b c"));
        assertEquals(bulks("c", "b", "a"), h.run("LRANGE l 0 -1"));
        assertEquals(":5\r\n", h.run("RPUSH l d e"));
        assertEquals(bulks("d", "e"), h.run("LRANGE l -2 -1"));
        assertEquals(bulks("c", "b", "a", "d", "e"), h.run("LRANGE l -100 100"));
        assertEquals("*0\r\n", h.run("LRANGE l 3 1"));
        assertEquals("*0\r\n", h.run("LRANGE l 5 10"));
        assertEquals(bulk("c"), h.run("LPOP l"));
        assertEquals(bulk("e"), h.run("RPOP l"));
        assertEquals(bulks("b", "a"), h.run("LPOP l 2"));
        assertEquals("-ERR value is out of range, must be positive\r\n", h.run("LPOP l -1"));
        assertEquals(bulks("d"), h.run("RPOP l 10"));
        assertEquals(":0\r\n", h.run("EXISTS l"), "an emptied list is deleted");
        assertEquals("$-1\r\n", h.run("LPOP l"));
        assertEquals("*-1\r\n", h.run("LPOP l 2"));
    }

    @Test
    void hashes() {
        Harness h = new Harness();
        assertEquals(":2\r\n", h.run("HSET h f1 v1 f2 v2"));
        assertEquals(":1\r\n", h.run("HSET h f1 new f3 v3"), "updates don't count");
        assertEquals(bulks("f1", "new", "f2", "v2", "f3", "v3"), h.run("HGETALL h"));
        assertEquals(":3\r\n", h.run("HLEN h"));
        assertEquals(":1\r\n", h.run("HEXISTS h f1"));
        assertEquals("-ERR hash value is not an integer\r\n", h.run("HINCRBY h f1 1"));
        assertEquals(":5\r\n", h.run("HINCRBY h n 5"));
        assertEquals(":2\r\n", h.run("HDEL h f1 f2 nosuch"));
        assertEquals(":2\r\n", h.run("HDEL h f3 n"));
        assertEquals(":0\r\n", h.run("EXISTS h"), "an emptied hash is deleted");
    }

    @Test
    void sortedSets() {
        Harness h = new Harness();
        assertEquals(":3\r\n", h.run("ZADD z 1 a 2 b 3 c"));
        assertEquals(":0\r\n", h.run("ZADD z 1.5 a"));
        assertEquals(bulks("a", "1.5", "b", "2", "c", "3"), h.run("ZRANGE z 0 -1 WITHSCORES"));
        assertEquals(":0\r\n", h.run("ZRANK z a"));
        assertEquals(":2\r\n", h.run("ZREVRANK z a"));
        assertEquals("$-1\r\n", h.run("ZRANK z nosuch"));
        assertEquals(bulk("1.5"), h.run("ZSCORE z a"));
        assertEquals("-ERR value is not a valid float\r\n", h.run("ZADD z nan x"));
        assertEquals("-ERR value is not a valid float\r\n", h.run("ZADD z 1 x abc y"));
        assertEquals(":3\r\n", h.run("ZCARD z"), "a bad score anywhere means no change at all");
        assertEquals(bulks("c", "b"), h.run("ZRANGE z 0 1 REV"));
        assertEquals(bulks("b", "c"), h.run("ZRANGE z (1.5 3 BYSCORE"));
        assertEquals(bulks("c", "b"), h.run("ZRANGE z 3 (1.5 BYSCORE REV"));
        assertEquals(bulks("b"), h.run("ZRANGEBYSCORE z 1 3 LIMIT 1 1"));
        assertEquals(":2\r\n", h.run("ZCOUNT z (1.5 3"));
        assertEquals(bulks("a", "1.5"), h.run("ZPOPMIN z"));
        assertEquals(":2\r\n", h.run("ZREM z b c nosuch"));
        assertEquals(":0\r\n", h.run("EXISTS z"), "an emptied sorted set is deleted");
        assertEquals(bulk("inf"), h.run("ZADD z INCR +inf m"));
    }

    @Test
    void configAndCommandForTheStockTools() {
        Harness h = new Harness();
        // redis-benchmark asks for these two at startup.
        assertEquals(bulks("save", ""), h.run("CONFIG GET save"));
        assertEquals(bulks("appendonly", "no"), h.run("CONFIG GET appendonly"));
        assertEquals("*0\r\n", h.run("CONFIG GET nosuch"));
        // redis-cli falls back to its built-in help when COMMAND DOCS errors.
        assertEquals('-', h.run("COMMAND DOCS").charAt(0));
    }

    @Test
    void keysPatterns() {
        Harness h = new Harness();
        h.run("MSET user:1 a user:2 b other c");
        String all = h.run("KEYS *");
        assertEquals('*', all.charAt(0));
        assertEquals("*3", all.substring(0, 2));
        String users = h.run("KEYS user:*");
        assertEquals("*2", users.substring(0, 2));
        assertEquals(bulks("other"), h.run("KEYS o?her"));
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
