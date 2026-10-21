package io.github.supersanchayrx.redis.protocol;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A growable byte buffer with one append method per RESP2 reply type. Every client owns one as its
 * output queue; the AOF uses one as its write buffer. Bytes are appended at {@code end} and drained
 * from {@code start}, so a partial socket write resumes exactly where it stopped.
 */
public final class Resp {

    private static final byte[] CRLF = {'\r', '\n'};

    public static final byte[] OK = ascii("+OK\r\n");
    public static final byte[] PONG = ascii("+PONG\r\n");
    public static final byte[] NULL_BULK = ascii("$-1\r\n");
    public static final byte[] NULL_ARRAY = ascii("*-1\r\n");
    public static final byte[] EMPTY_ARRAY = ascii("*0\r\n");
    public static final byte[] ZERO = ascii(":0\r\n");
    public static final byte[] ONE = ascii(":1\r\n");

    /** Above this, an emptied buffer is given back instead of being kept for reuse. */
    private static final int KEEP_CAPACITY = 64 * 1024;
    private static final int INITIAL_CAPACITY = 1024;
    /** Upper bound on bytes handed to one {@code write} call, so the JDK's temporary direct buffer stays small. */
    private static final int MAX_WRITE_CHUNK = 256 * 1024;

    private byte[] buf;
    private int start;
    private int end;

    public Resp() {
        this(INITIAL_CAPACITY);
    }

    public Resp(int capacity) {
        buf = new byte[capacity];
    }

    // ---- reply types ----

    public Resp simple(String s) {
        put((byte) '+');
        putAscii(s);
        return raw(CRLF);
    }

    /** An error line. {@code message} is the text after the dash, for example {@code "ERR syntax error"}. */
    public Resp error(String message) {
        put((byte) '-');
        // An error is one line: Redis replaces CR and LF in the middle with spaces, and so do we.
        byte[] b = message.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < b.length; i++) {
            if (b[i] == '\r' || b[i] == '\n') {
                b[i] = ' ';
            }
        }
        raw(b);
        return raw(CRLF);
    }

    public Resp integer(long v) {
        if (v == 0) {
            return raw(ZERO);
        }
        if (v == 1) {
            return raw(ONE);
        }
        put((byte) ':');
        putLong(v);
        return raw(CRLF);
    }

    public Resp bulk(byte[] b) {
        if (b == null) {
            return raw(NULL_BULK);
        }
        put((byte) '$');
        putLong(b.length);
        raw(CRLF);
        raw(b);
        return raw(CRLF);
    }

    public Resp bulk(String s) {
        return bulk(s.getBytes(StandardCharsets.UTF_8));
    }

    /** A long as a bulk string, the way Redis sends numbers inside arrays (for example CONFIG GET values). */
    public Resp bulk(long v) {
        return bulk(Long.toString(v).getBytes(StandardCharsets.US_ASCII));
    }

    public Resp nullBulk() {
        return raw(NULL_BULK);
    }

    public Resp arrayHeader(int count) {
        put((byte) '*');
        putLong(count);
        return raw(CRLF);
    }

    public Resp nullArray() {
        return raw(NULL_ARRAY);
    }

    /** A whole command as a RESP array of bulk strings: the AOF format, and what a client sends. */
    public Resp command(byte[]... argv) {
        arrayHeader(argv.length);
        for (byte[] a : argv) {
            bulk(a);
        }
        return this;
    }

    public Resp raw(byte[] b) {
        return raw(b, 0, b.length);
    }

    public Resp raw(byte[] b, int off, int len) {
        ensure(len);
        System.arraycopy(b, off, buf, end, len);
        end += len;
        return this;
    }

    // ---- draining ----

    public int pending() {
        return end - start;
    }

    public boolean isEmpty() {
        return end == start;
    }

    /**
     * Writes as much as the channel takes, up to {@code maxBytes}. Returns the bytes written; the rest stays
     * queued. A short write is normal for a non-blocking socket, not an error.
     */
    public long writeTo(WritableByteChannel ch, long maxBytes) throws IOException {
        long total = 0;
        while (start < end && total < maxBytes) {
            int len = (int) Math.min(Math.min(end - start, MAX_WRITE_CHUNK), maxBytes - total);
            int n = ch.write(ByteBuffer.wrap(buf, start, len));
            start += n;
            total += n;
            if (n < len) {
                break;
            }
        }
        if (start == end) {
            clear();
        }
        return total;
    }

    /** The pending bytes, without copying. The caller drains them and then calls {@link #clear()}. */
    public ByteBuffer pendingView() {
        return ByteBuffer.wrap(buf, start, end - start);
    }

    public void clear() {
        start = 0;
        end = 0;
        if (buf.length > KEEP_CAPACITY) {
            buf = new byte[INITIAL_CAPACITY];
        }
    }

    /** A copy of the pending bytes, for tests. */
    public byte[] toByteArray() {
        return Arrays.copyOfRange(buf, start, end);
    }

    @Override
    public String toString() {
        return new String(buf, start, end - start, StandardCharsets.UTF_8);
    }

    // ---- internals ----

    private void put(byte b) {
        ensure(1);
        buf[end++] = b;
    }

    private void putAscii(String s) {
        int n = s.length();
        ensure(n);
        for (int i = 0; i < n; i++) {
            buf[end++] = (byte) s.charAt(i);
        }
    }

    /** ASCII digits straight into the buffer, without building a String. */
    private void putLong(long v) {
        if (v == Long.MIN_VALUE) {
            putAscii("-9223372036854775808");
            return;
        }
        ensure(20);
        if (v < 0) {
            buf[end++] = '-';
            v = -v;
        }
        int digits = 1;
        for (long t = v; t >= 10; t /= 10) {
            digits++;
        }
        int p = end + digits;
        end = p;
        do {
            buf[--p] = (byte) ('0' + (v % 10));
            v /= 10;
        } while (v != 0);
    }

    private void ensure(int extra) {
        if (end + extra <= buf.length) {
            return;
        }
        int live = end - start;
        if (start > 0 && live + extra <= buf.length / 2) {
            // Plenty of room once the drained prefix is reclaimed.
            System.arraycopy(buf, start, buf, 0, live);
        } else {
            long want = Math.max((long) buf.length * 2, (long) live + extra);
            if (want > Integer.MAX_VALUE - 8) {
                throw new OutOfMemoryError("reply buffer over 2 GB");
            }
            byte[] bigger = new byte[(int) want];
            System.arraycopy(buf, start, bigger, 0, live);
            buf = bigger;
        }
        start = 0;
        end = live;
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }
}
