package io.github.supersanchayrx.redis.protocol;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits an inline command line into arguments the way Redis's {@code sdssplitargs} does: words separated
 * by whitespace, with "double quotes" (supporting \n \r \t \b \a \\ \" and \xHH escapes) and 'single quotes'
 * (supporting \'). A closing quote must be followed by whitespace or the end of the line.
 */
final class InlineSplitter {

    private static final byte[][] NONE = new byte[0][];

    private InlineSplitter() {
    }

    static byte[][] split(byte[] b, int from, int to) throws ProtocolException {
        List<byte[]> out = null;
        int p = from;
        while (true) {
            while (p < to && isSpace(b[p])) {
                p++;
            }
            if (p >= to) {
                break;
            }
            ByteArrayOutputStream cur = new ByteArrayOutputStream();
            boolean inDq = false;
            boolean inSq = false;
            boolean done = false;
            while (!done) {
                if (inDq) {
                    if (p >= to) {
                        throw new ProtocolException("unbalanced quotes in request");
                    }
                    if (b[p] == '\\' && p + 3 < to && b[p + 1] == 'x' && isHex(b[p + 2]) && isHex(b[p + 3])) {
                        cur.write(hex(b[p + 2]) * 16 + hex(b[p + 3]));
                        p += 3;
                    } else if (b[p] == '\\' && p + 1 < to) {
                        p++;
                        cur.write(switch (b[p]) {
                            case 'n' -> '\n';
                            case 'r' -> '\r';
                            case 't' -> '\t';
                            case 'b' -> '\b';
                            case 'a' -> 7;
                            default -> b[p];
                        });
                    } else if (b[p] == '"') {
                        if (p + 1 < to && !isSpace(b[p + 1])) {
                            throw new ProtocolException("unbalanced quotes in request");
                        }
                        done = true;
                    } else {
                        cur.write(b[p]);
                    }
                } else if (inSq) {
                    if (p >= to) {
                        throw new ProtocolException("unbalanced quotes in request");
                    }
                    if (b[p] == '\\' && p + 1 < to && b[p + 1] == '\'') {
                        p++;
                        cur.write('\'');
                    } else if (b[p] == '\'') {
                        if (p + 1 < to && !isSpace(b[p + 1])) {
                            throw new ProtocolException("unbalanced quotes in request");
                        }
                        done = true;
                    } else {
                        cur.write(b[p]);
                    }
                } else {
                    if (p >= to) {
                        break;
                    }
                    switch (b[p]) {
                        case ' ', '\n', '\r', '\t', 0 -> done = true;
                        case '"' -> inDq = true;
                        case '\'' -> inSq = true;
                        default -> cur.write(b[p]);
                    }
                }
                if (p < to) {
                    p++;
                }
            }
            if (out == null) {
                out = new ArrayList<>(4);
            }
            out.add(cur.toByteArray());
        }
        return out == null ? NONE : out.toArray(new byte[0][]);
    }

    private static boolean isSpace(byte c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0x0b || c == '\f';
    }

    private static boolean isHex(byte c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static int hex(byte c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        return (c | 0x20) - 'a' + 10;
    }
}
