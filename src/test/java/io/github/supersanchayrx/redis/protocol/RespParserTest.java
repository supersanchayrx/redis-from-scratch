package io.github.supersanchayrx.redis.protocol;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RespParserTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] encode(byte[]... argv) {
        return new Resp().command(argv).toByteArray();
    }

    /** Commands covering the awkward shapes: empty args, CRLF inside a bulk, high bytes, a 100 KB bulk. */
    private static List<byte[][]> samples() {
        byte[] big = new byte[100 * 1024];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) (i * 31 + 7);
        }
        return List.of(
                new byte[][] {b("PING")},
                new byte[][] {b("SET"), b("key"), b("value")},
                new byte[][] {b("SET"), b(""), b("")},
                new byte[][] {b("SET"), b("k\r\n"), b("\r\n\r\nx")},
                new byte[][] {b("SET"), new byte[] {0, (byte) 0xff, (byte) 0x80}, b("*3\r\n$1\r\n")},
                new byte[][] {b("SET"), b("big"), big});
    }

    private static List<byte[][]> parseAll(RespParser p) throws ProtocolException {
        return p.drain();
    }

    private static void assertSame(byte[][] expected, byte[][] actual) {
        assertEquals(expected.length, actual.length, "argument count");
        for (int i = 0; i < expected.length; i++) {
            assertArrayEquals(expected[i], actual[i], "argument " + i);
        }
    }

    @Test
    void wholeCommands() throws Exception {
        for (byte[][] cmd : samples()) {
            RespParser p = new RespParser();
            p.feed(encode(cmd));
            List<byte[][]> got = parseAll(p);
            assertEquals(1, got.size());
            assertSame(cmd, got.get(0));
            assertEquals(0, p.buffered());
        }
    }

    @Test
    void oneByteAtATime() throws Exception {
        for (byte[][] cmd : samples()) {
            byte[] wire = encode(cmd);
            RespParser p = new RespParser();
            List<byte[][]> got = new ArrayList<>();
            for (int i = 0; i < wire.length; i++) {
                p.feed(wire, i, 1);
                got.addAll(parseAll(p));
                if (i < wire.length - 1) {
                    assertTrue(got.isEmpty(), "a command came out early, at byte " + i);
                }
            }
            assertEquals(1, got.size());
            assertSame(cmd, got.get(0));
        }
    }

    @Test
    void everyTwoWaySplit() throws Exception {
        for (byte[][] cmd : samples()) {
            byte[] wire = encode(cmd);
            // The 100 KB sample has ~100k split points; every 97th is plenty and keeps the test fast.
            int step = wire.length > 10_000 ? 97 : 1;
            for (int split = 0; split <= wire.length; split += step) {
                RespParser p = new RespParser();
                p.feed(wire, 0, split);
                List<byte[][]> got = new ArrayList<>(parseAll(p));
                p.feed(wire, split, wire.length - split);
                got.addAll(parseAll(p));
                assertEquals(1, got.size(), "split at " + split);
                assertSame(cmd, got.get(0));
            }
        }
    }

    @Test
    void pipelineOfAThousand() throws Exception {
        Resp out = new Resp();
        for (int i = 0; i < 1000; i++) {
            out.command(b("SET"), b("key:" + i), b("v" + i));
        }
        RespParser p = new RespParser();
        p.feed(out.toByteArray());
        List<byte[][]> got = parseAll(p);
        assertEquals(1000, got.size());
        for (int i = 0; i < 1000; i++) {
            assertArrayEquals(b("key:" + i), got.get(i)[1]);
        }
    }

    @Test
    void twoAndAHalfCommands() throws Exception {
        byte[] one = encode(b("GET"), b("a"));
        byte[] three = encode(b("SET"), b("c"), b("value"));
        byte[] wire = new byte[one.length * 2 + three.length];
        System.arraycopy(one, 0, wire, 0, one.length);
        System.arraycopy(one, 0, wire, one.length, one.length);
        System.arraycopy(three, 0, wire, one.length * 2, three.length);
        int cut = one.length * 2 + three.length / 2;

        RespParser p = new RespParser();
        p.feed(wire, 0, cut);
        assertEquals(2, parseAll(p).size());
        assertTrue(p.buffered() > 0 || !p.atCommandBoundary(), "the half command is kept");
        p.feed(wire, cut, wire.length - cut);
        List<byte[][]> rest = parseAll(p);
        assertEquals(1, rest.size());
        assertSame(new byte[][] {b("SET"), b("c"), b("value")}, rest.get(0));
    }

    @Test
    void readBufferPath() throws Exception {
        // The socket path: read into readBuffer(), then commitRead().
        RespParser p = new RespParser();
        byte[] wire = encode(b("ECHO"), b("hi"));
        ByteBuffer dst = p.readBuffer();
        assertTrue(dst.remaining() >= RespParser.IOBUF_LEN);
        dst.put(wire);
        p.commitRead();
        assertSame(new byte[][] {b("ECHO"), b("hi")}, p.next());
        assertNull(p.next());
    }

    @Test
    void largeArgumentGrowsTheBufferGradually() throws Exception {
        // Announce 1 MB but send only 10 bytes: the buffer must not jump to 1 MB.
        RespParser p = new RespParser();
        p.feed(b("*2\r\n$3\r\nSET\r\n$1048576\r\n0123456789"));
        assertNull(p.next());
        assertTrue(p.readBuffer().capacity() < 100_000, "buffer jumped to the announced size");
    }

    @Test
    void emptyMultibulkIsSkipped() throws Exception {
        RespParser p = new RespParser();
        p.feed(b("*0\r\n*-1\r\n*1\r\n$4\r\nPING\r\n"));
        List<byte[][]> got = parseAll(p);
        assertEquals(1, got.size());
        assertSame(new byte[][] {b("PING")}, got.get(0));
    }

    @Test
    void inlineCommands() throws Exception {
        RespParser p = new RespParser();
        p.feed(b("PING\r\nSET a b\n  SET   x   y  \r\n\r\nGET \"hello world\" 'it\\'s'\r\n"));
        List<byte[][]> got = parseAll(p);
        assertEquals(4, got.size());
        assertSame(new byte[][] {b("PING")}, got.get(0));
        assertSame(new byte[][] {b("SET"), b("a"), b("b")}, got.get(1));
        assertSame(new byte[][] {b("SET"), b("x"), b("y")}, got.get(2));
        assertEquals("hello world", new String(got.get(3)[1], StandardCharsets.UTF_8));
        assertEquals("it's", new String(got.get(3)[2], StandardCharsets.UTF_8));
    }

    @Test
    void inlineEscapes() throws Exception {
        RespParser p = new RespParser();
        p.feed(b("SET k \"a\\r\\n\\x41\\\"\"\r\n"));
        assertArrayEquals(b("a\r\nA\""), p.next()[2]);
    }

    // ---- errors: Redis's exact text (networking.c) ----

    private static String error(String input) {
        RespParser p = new RespParser();
        p.feed(b(input));
        return assertThrows(ProtocolException.class, () -> parseAll(p)).getMessage();
    }

    @Test
    void protocolErrors() {
        assertEquals("Protocol error: invalid multibulk length", error("*abc\r\n"));
        assertEquals("Protocol error: invalid multibulk length", error("*3000000000\r\n"));
        assertEquals("Protocol error: invalid multibulk length", error("*+1\r\n"));
        assertEquals("Protocol error: expected '$', got 'x'", error("*1\r\nx\r\n"));
        assertEquals("Protocol error: invalid bulk length", error("*1\r\n$abc\r\n"));
        assertEquals("Protocol error: invalid bulk length", error("*1\r\n$-5\r\n"));
        assertEquals("Protocol error: invalid bulk length", error("*1\r\n$600000000\r\n"));
        assertEquals("Protocol error: unbalanced quotes in request", error("SET \"abc\r\n"));
        assertEquals("Protocol error: unbalanced quotes in request", error("SET \"abc\"x\r\n"));
    }

    @Test
    void oversizedLines() {
        char[] longLine = new char[70 * 1024];
        Arrays.fill(longLine, 'a');
        assertEquals("Protocol error: too big inline request", error(new String(longLine)));
        assertEquals("Protocol error: too big mbulk count string", error("*" + new String(longLine)));
        assertEquals("Protocol error: too big bulk count string", error("*1\r\n$" + new String(longLine)));
    }

    @Test
    void aofParserRejectsInline() {
        RespParser p = new RespParser(false);
        p.feed(b("SET a b\r\n"));
        assertEquals("Protocol error: expected '*', got 'S'",
                assertThrows(ProtocolException.class, p::next).getMessage());
    }
}
