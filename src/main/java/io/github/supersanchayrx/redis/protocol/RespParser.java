package io.github.supersanchayrx.redis.protocol;

import io.github.supersanchayrx.redis.util.Bytes;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Incremental RESP2 request parser. It owns the client's input buffer and keeps its state between reads,
 * so a command split across TCP packets parses the same as one that arrived whole, and every complete
 * command in a pipelined buffer is returned in turn.
 *
 * <p>The buffer holds the unconsumed bytes in {@code buf[pos..end)}. The parser only consumes a unit
 * (a header line, or a whole bulk argument plus its CRLF) once all of it is present; arguments are copied
 * out as soon as they complete, so the buffer never holds more than one partial unit.
 */
public final class RespParser {

    /** Redis's PROTO_IOBUF_LEN: the read size per readable event. */
    public static final int IOBUF_LEN = 16 * 1024;
    /** Redis's PROTO_INLINE_MAX_SIZE: the longest inline command, and the longest header line. */
    public static final int INLINE_MAX = 64 * 1024;
    /** Redis's default proto-max-bulk-len (512 MB). The buffer only grows as bytes actually arrive. */
    public static final long MAX_BULK = 512L * 1024 * 1024;
    /** Argument arrays start at most this big and grow as arguments arrive, so "*2000000000" allocates nothing. */
    private static final int INITIAL_ARGS_CAP = 1024;

    private enum State { START, INLINE, ARRAY_HEADER, BULK_HEADER, BULK_BODY }

    private final boolean allowInline;

    private byte[] buf = new byte[IOBUF_LEN];
    private int pos;
    private int end;
    private ByteBuffer view = ByteBuffer.wrap(buf);

    private State state = State.START;
    /** Where the next search for a line ending starts, so a long line isn't rescanned on every read. */
    private int scan;
    private byte[][] args;
    private int argCount;
    private int argsExpected;
    private int bulkLen;

    /** A parser for client sockets: RESP arrays and inline commands. */
    public RespParser() {
        this(true);
    }

    /** @param allowInline false for the AOF, which must hold only RESP arrays */
    public RespParser(boolean allowInline) {
        this.allowInline = allowInline;
    }

    // ---- feeding bytes ----

    /**
     * The buffer to read the socket into: position at the end of the unconsumed bytes, limit at capacity,
     * with at least {@link #IOBUF_LEN} free. Call {@link #commitRead()} after reading into it.
     */
    public ByteBuffer readBuffer() {
        reserve(IOBUF_LEN);
        view.limit(buf.length).position(end);
        return view;
    }

    /** Records the bytes the last read put into {@link #readBuffer()}. */
    public void commitRead() {
        end = view.position();
    }

    /** Appends bytes directly: for tests and for the AOF loader. */
    public void feed(byte[] data, int off, int len) {
        reserve(len);
        System.arraycopy(data, off, buf, end, len);
        end += len;
    }

    public void feed(byte[] data) {
        feed(data, 0, data.length);
    }

    /** Bytes received but not yet consumed by a complete unit. */
    public int buffered() {
        return end - pos;
    }

    /** True between commands: no partial command is held in the parser state. */
    public boolean atCommandBoundary() {
        return state == State.START;
    }

    // ---- parsing ----

    /** Every complete command currently available. */
    public List<byte[][]> drain() throws ProtocolException {
        List<byte[][]> out = new ArrayList<>();
        byte[][] cmd;
        while ((cmd = next()) != null) {
            out.add(cmd);
        }
        return out;
    }

    /** The next complete command, or null if more bytes are needed. */
    public byte[][] next() throws ProtocolException {
        while (true) {
            switch (state) {
                case START -> {
                    if (pos == end) {
                        compactIfEmpty();
                        return null;
                    }
                    if (buf[pos] == '*') {
                        state = State.ARRAY_HEADER;
                    } else if (allowInline) {
                        state = State.INLINE;
                    } else {
                        throw new ProtocolException("expected '*', got '" + (char) (buf[pos] & 0xff) + "'");
                    }
                    scan = pos;
                }
                case INLINE -> {
                    int nl = indexOf((byte) '\n');
                    if (nl < 0) {
                        if (end - pos > INLINE_MAX) {
                            throw new ProtocolException("too big inline request");
                        }
                        return null;
                    }
                    int lineEnd = (nl > pos && buf[nl - 1] == '\r') ? nl - 1 : nl;
                    byte[][] argv = InlineSplitter.split(buf, pos, lineEnd);
                    pos = nl + 1;
                    state = State.START;
                    if (argv.length > 0) {
                        return argv;
                    }
                }
                case ARRAY_HEADER -> {
                    int cr = lineEnd("too big mbulk count string");
                    if (cr < 0) {
                        return null;
                    }
                    long count;
                    try {
                        count = Bytes.parseLong(buf, pos + 1, cr - pos - 1);
                    } catch (Bytes.NotANumber e) {
                        throw new ProtocolException("invalid multibulk length");
                    }
                    if (count > Integer.MAX_VALUE) {
                        throw new ProtocolException("invalid multibulk length");
                    }
                    pos = cr + 2;
                    if (count <= 0) {
                        state = State.START; // "*0" and "*-1" are empty commands; Redis skips them too
                        continue;
                    }
                    argsExpected = (int) count;
                    args = new byte[Math.min(argsExpected, INITIAL_ARGS_CAP)][];
                    argCount = 0;
                    state = State.BULK_HEADER;
                    scan = pos;
                }
                case BULK_HEADER -> {
                    if (pos == end) {
                        return null;
                    }
                    if (buf[pos] != '$') {
                        throw new ProtocolException("expected '$', got '" + (char) (buf[pos] & 0xff) + "'");
                    }
                    int cr = lineEnd("too big bulk count string");
                    if (cr < 0) {
                        return null;
                    }
                    long len;
                    try {
                        len = Bytes.parseLong(buf, pos + 1, cr - pos - 1);
                    } catch (Bytes.NotANumber e) {
                        throw new ProtocolException("invalid bulk length");
                    }
                    if (len < 0 || len > MAX_BULK) {
                        throw new ProtocolException("invalid bulk length");
                    }
                    bulkLen = (int) len;
                    pos = cr + 2;
                    state = State.BULK_BODY;
                }
                case BULK_BODY -> {
                    if (end - pos < (long) bulkLen + 2) {
                        return null;
                    }
                    if (argCount == args.length) {
                        args = Arrays.copyOf(args, Math.min(argsExpected, args.length * 2));
                    }
                    // Like Redis, the two bytes after the payload are skipped without checking them.
                    args[argCount++] = Arrays.copyOfRange(buf, pos, pos + bulkLen);
                    pos += bulkLen + 2;
                    if (argCount == argsExpected) {
                        byte[][] argv = args;
                        args = null;
                        state = State.START;
                        return argv;
                    }
                    state = State.BULK_HEADER;
                    scan = pos;
                }
            }
        }
    }

    /**
     * The index of the CR ending the header line at {@code pos}, or -1 if the line (or the LF after
     * its CR) hasn't fully arrived. Redis looks for '\r' and then skips two bytes.
     */
    private int lineEnd(String tooBigMessage) throws ProtocolException {
        int cr = indexOf((byte) '\r');
        if (cr < 0 || cr + 1 >= end) {
            if (end - pos > INLINE_MAX) {
                throw new ProtocolException(tooBigMessage);
            }
            return -1;
        }
        return cr;
    }

    private int indexOf(byte b) {
        for (int i = Math.max(scan, pos); i < end; i++) {
            if (buf[i] == b) {
                return i;
            }
        }
        scan = end;
        return -1;
    }

    /** Makes room for {@code free} more bytes after {@code end}, reclaiming the consumed prefix first. */
    private void reserve(int free) {
        if (buf.length - end >= free) {
            return;
        }
        int live = end - pos;
        if (pos > 0 && buf.length - live >= free) {
            System.arraycopy(buf, pos, buf, 0, live);
        } else {
            // Doubling as data arrives: an announced "$500000000" never allocates 500 MB up front.
            int want = Math.max(buf.length * 2, live + free);
            byte[] bigger = new byte[want];
            System.arraycopy(buf, pos, bigger, 0, live);
            buf = bigger;
            view = ByteBuffer.wrap(buf);
        }
        scan -= pos;
        if (scan < 0) {
            scan = 0;
        }
        end = live;
        pos = 0;
    }

    /** Gives back a buffer that grew for one big argument, once it has drained. */
    private void compactIfEmpty() {
        pos = 0;
        end = 0;
        scan = 0;
        if (buf.length > 4 * IOBUF_LEN) {
            buf = new byte[IOBUF_LEN];
            view = ByteBuffer.wrap(buf);
        }
    }
}
