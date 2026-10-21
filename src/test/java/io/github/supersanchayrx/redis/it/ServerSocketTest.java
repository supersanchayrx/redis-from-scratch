package io.github.supersanchayrx.redis.it;

import io.github.supersanchayrx.redis.Config;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End to end over real sockets, against a server on a random port. */
class ServerSocketTest {

    @Test
    void pingRespAndInline() throws Exception {
        try (TestServer s = new TestServer(); RespClient c = s.client()) {
            assertEquals("+PONG\r\n", c.call("PING"));
            c.sendRaw("PING\r\n".getBytes(StandardCharsets.US_ASCII));
            assertEquals("+PONG\r\n", c.read());
            c.sendRaw("SET a b\nGET a\r\n".getBytes(StandardCharsets.US_ASCII));
            assertEquals("+OK\r\n", c.read());
            assertEquals("$1\r\nb\r\n", c.read());
        }
    }

    @Test
    void commandSplitAcrossPackets() throws Exception {
        try (TestServer s = new TestServer(); RespClient c = s.client()) {
            byte[] wire = RespClient.encode("SET", "split", "value");
            for (byte b : wire) {
                c.sendRaw(new byte[] {b});
            }
            assertEquals("+OK\r\n", c.read());
            assertEquals("$5\r\nvalue\r\n", c.call("GET", "split"));
        }
    }

    @Test
    void pipelinedCommandsAllRunInOrder() throws Exception {
        try (TestServer s = new TestServer(); RespClient c = s.client()) {
            ByteArrayOutputStream batch = new ByteArrayOutputStream();
            for (int i = 0; i < 1000; i++) {
                batch.writeBytes(RespClient.encode("INCR", "n"));
            }
            c.sendRaw(batch.toByteArray());
            for (int i = 1; i <= 1000; i++) {
                assertEquals(":" + i + "\r\n", c.read());
            }
        }
    }

    @Test
    void fiftyClientsAtOnce() throws Exception {
        try (TestServer s = new TestServer()) {
            List<RespClient> clients = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                clients.add(s.client());
            }
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                order.add(i);
            }
            Collections.shuffle(order, new java.util.Random(7));
            for (int i : order) {
                clients.get(i).send("ECHO", "c" + i);
            }
            for (int i = 0; i < 50; i++) {
                assertEquals("$" + ("c" + i).length() + "\r\nc" + i + "\r\n", clients.get(i).read());
            }
            for (RespClient c : clients) {
                c.close();
            }
        }
    }

    @Test
    void aClientThatStopsReadingDoesNotBlockOthers() throws Exception {
        try (TestServer s = new TestServer(); RespClient slow = s.client(); RespClient fast = s.client()) {
            ByteArrayOutputStream batch = new ByteArrayOutputStream();
            for (int i = 0; i < 10_000; i++) {
                batch.writeBytes(RespClient.encode("ECHO", "x".repeat(100)));
            }
            slow.sendRaw(batch.toByteArray()); // ~1 MB of replies it never reads
            long start = System.nanoTime();
            assertEquals("+PONG\r\n", fast.call("PING"));
            assertTrue(System.nanoTime() - start < 2_000_000_000L, "the fast client waited on the slow one");
        }
    }

    @Test
    void protocolErrorRepliesThenCloses() throws Exception {
        try (TestServer s = new TestServer(); RespClient c = s.client(); RespClient other = s.client()) {
            c.sendRaw("*1\r\n$abc\r\n".getBytes(StandardCharsets.US_ASCII));
            assertEquals("-ERR Protocol error: invalid bulk length\r\n", c.read());
            assertTrue(c.closedByServer());
            assertEquals("+PONG\r\n", other.call("PING"), "other clients don't notice");
        }
    }

    @Test
    void quitClosesAfterReply() throws Exception {
        try (TestServer s = new TestServer(); RespClient c = s.client()) {
            c.sendRaw((new String(RespClient.encode("QUIT")) + new String(RespClient.encode("PING")))
                    .getBytes(StandardCharsets.US_ASCII));
            assertEquals("+OK\r\n", c.read());
            assertTrue(c.closedByServer(), "nothing after QUIT runs");
        }
    }

    @Test
    void largeValues() throws Exception {
        try (TestServer s = new TestServer(); RespClient c = s.client()) {
            String big = "v".repeat(2 * 1024 * 1024);
            assertEquals("+OK\r\n", c.call("SET", "big", big));
            assertEquals("$" + big.length() + "\r\n" + big + "\r\n", c.call("GET", "big"));
        }
    }

    @Test
    void idleLoopDoesNotSpin() throws Exception {
        try (TestServer s = new TestServer(); RespClient c = s.client()) {
            assertEquals("+PONG\r\n", c.call("PING"));
            long before = iterations(c);
            Thread.sleep(1000);
            long after = iterations(c);
            // At hz 10 the loop wakes for timers ~10 times a second; an OP_WRITE spin would be millions.
            assertTrue(after - before < 60, "loop iterations in 1 s while idle: " + (after - before));
        }
    }

    private static long iterations(RespClient c) throws Exception {
        String info = c.call("INFO");
        for (String line : info.split("\r\n")) {
            if (line.startsWith("event_loop_iterations:")) {
                return Long.parseLong(line.substring("event_loop_iterations:".length()));
            }
        }
        throw new AssertionError("no event_loop_iterations in INFO");
    }

    // ---- pub/sub ----

    private static String sub(String kind, String ch, int count) {
        return "*3\r\n$" + kind.length() + "\r\n" + kind + "\r\n$" + ch.length() + "\r\n" + ch + "\r\n:" + count + "\r\n";
    }

    @Test
    void subscribeCountsAndUnsubscribe() throws Exception {
        try (TestServer s = new TestServer(); RespClient c = s.client()) {
            c.send("SUBSCRIBE", "a", "b", "c");
            assertEquals(sub("subscribe", "a", 1), c.read());
            assertEquals(sub("subscribe", "b", 2), c.read());
            assertEquals(sub("subscribe", "c", 3), c.read());
            assertEquals(sub("unsubscribe", "b", 2), c.call("UNSUBSCRIBE", "b"));
            assertEquals("-ERR Can't execute 'get': only (P|S)SUBSCRIBE / (P|S)UNSUBSCRIBE / PING / QUIT / RESET"
                    + " are allowed in this context\r\n", c.call("GET", "x"));
            assertEquals("*2\r\n$4\r\npong\r\n$0\r\n\r\n", c.call("PING"));
            c.send("UNSUBSCRIBE");
            assertEquals(sub("unsubscribe", "a", 1), c.read());
            assertEquals(sub("unsubscribe", "c", 0), c.read());
            assertEquals("*3\r\n$11\r\nunsubscribe\r\n$-1\r\n:0\r\n", c.call("UNSUBSCRIBE"));
            assertEquals("$-1\r\n", c.call("GET", "x"), "out of subscribed mode again");
        }
    }

    @Test
    void publishReachesEverySubscriber() throws Exception {
        try (TestServer s = new TestServer(); RespClient pub = s.client()) {
            assertEquals(":0\r\n", pub.call("PUBLISH", "news", "nobody"));
            List<RespClient> subs = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                RespClient c = s.client();
                assertEquals(sub("subscribe", "news", 1), c.call("SUBSCRIBE", "news"));
                subs.add(c);
            }
            RespClient pat = s.client();
            assertEquals(sub("psubscribe", "n*", 1), pat.call("PSUBSCRIBE", "n*"));
            assertEquals(":4\r\n", pub.call("PUBLISH", "news", "hello"));
            for (RespClient c : subs) {
                assertEquals("*3\r\n$7\r\nmessage\r\n$4\r\nnews\r\n$5\r\nhello\r\n", c.read());
            }
            assertEquals("*4\r\n$8\r\npmessage\r\n$2\r\nn*\r\n$4\r\nnews\r\n$5\r\nhello\r\n", pat.read());

            subs.get(0).close();
            long deadline = System.currentTimeMillis() + 5000;
            String n;
            do {
                n = pub.call("PUBLISH", "news", "again");
            } while (!n.equals(":3\r\n") && System.currentTimeMillis() < deadline);
            assertEquals(":3\r\n", n, "a disconnected subscriber is removed");
            pat.close();
            for (RespClient c : subs.subList(1, 3)) {
                c.close();
            }
        }
    }

    @Test
    void slowSubscriberIsCutOffAtTheHardLimit() throws Exception {
        Config config = new Config();
        config.pubsubHardLimit = 1024 * 1024; // 1 MB, so the test doesn't need 32 MB in flight
        try (TestServer s = new TestServer(config);
             RespClient slow = new RespClient("127.0.0.1", s.port, 4096);
             RespClient pub = s.client()) {
            assertEquals(sub("subscribe", "flood", 1), slow.call("SUBSCRIBE", "flood"));
            String payload = "p".repeat(10 * 1024);
            byte[] batch;
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            for (int i = 0; i < 50; i++) {
                b.writeBytes(RespClient.encode("PUBLISH", "flood", payload));
            }
            batch = b.toByteArray();
            int batches = 0;
            boolean cut = false;
            while (batches < 2000 && !cut) {
                pub.sendRaw(batch);
                for (int i = 0; i < 50; i++) {
                    pub.read();
                }
                batches++;
                cut = pub.call("PUBSUB", "NUMSUB", "flood").endsWith(":0\r\n");
            }
            assertTrue(cut, "the subscriber was never disconnected");
            assertTrue(slow.socket.isConnected());
            assertEquals("+PONG\r\n", pub.call("PING"), "the publisher never blocked");
        }
    }
}
