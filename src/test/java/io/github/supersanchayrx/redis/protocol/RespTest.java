package io.github.supersanchayrx.redis.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Encoder output, byte for byte, against the RESP2 forms real Redis sends. */
class RespTest {

    private static String s(Resp r) {
        return new String(r.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    @Test
    void replyTypes() {
        assertEquals("+OK\r\n", s(new Resp().simple("OK")));
        assertEquals("-ERR syntax error\r\n", s(new Resp().error("ERR syntax error")));
        assertEquals("-ERR a  b c\r\n", s(new Resp().error("ERR a\r\nb\nc")), "each CR and LF becomes a space");
        assertEquals(":0\r\n", s(new Resp().integer(0)));
        assertEquals(":1\r\n", s(new Resp().integer(1)));
        assertEquals(":-2\r\n", s(new Resp().integer(-2)));
        assertEquals(":1000\r\n", s(new Resp().integer(1000)));
        assertEquals(":9223372036854775807\r\n", s(new Resp().integer(Long.MAX_VALUE)));
        assertEquals(":-9223372036854775808\r\n", s(new Resp().integer(Long.MIN_VALUE)));
        assertEquals("$5\r\nhello\r\n", s(new Resp().bulk("hello")));
        assertEquals("$0\r\n\r\n", s(new Resp().bulk(new byte[0])));
        assertEquals("$-1\r\n", s(new Resp().bulk((byte[]) null)));
        assertEquals("*-1\r\n", s(new Resp().nullArray()));
        assertEquals("*2\r\n$3\r\nGET\r\n$1\r\na\r\n", s(new Resp().command("GET".getBytes(), "a".getBytes())));
    }

    @Test
    void utf8LengthIsInBytes() {
        assertEquals("$2\r\nÃ©\r\n", s(new Resp().bulk("é")));
    }

    @Test
    void partialWritesResumeWhereTheyStopped() throws Exception {
        Resp r = new Resp();
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            r.bulk("value-" + i);
            expected.append("$").append(("value-" + i).length()).append("\r\nvalue-").append(i).append("\r\n");
        }
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        WritableByteChannel inner = Channels.newChannel(sink);
        // A channel that takes at most 7 bytes per call, like a congested socket.
        WritableByteChannel stingy = new WritableByteChannel() {
            @Override
            public int write(java.nio.ByteBuffer src) throws java.io.IOException {
                int lim = src.limit();
                src.limit(Math.min(lim, src.position() + 7));
                int n = inner.write(src);
                src.limit(lim);
                return n;
            }

            @Override
            public boolean isOpen() {
                return true;
            }

            @Override
            public void close() {
            }
        };
        int calls = 0;
        while (!r.isEmpty()) {
            r.writeTo(stingy, Long.MAX_VALUE);
            calls++;
            if (calls % 3 == 0) {
                r.bulk("x"); // more replies arriving between partial writes
                expected.append("$1\r\nx\r\n");
            }
            if (calls > 100_000) {
                break;
            }
        }
        assertTrue(r.isEmpty());
        assertEquals(expected.toString(), sink.toString(StandardCharsets.ISO_8859_1));
    }
}
