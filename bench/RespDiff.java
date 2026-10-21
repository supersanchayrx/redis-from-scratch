import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Sends the same commands to two servers and compares the raw reply bytes.
 * Usage: java bench/RespDiff.java <hostA:port> <hostB:port> <commands-file>
 * One command per line, words split on spaces, "double quotes" for words with spaces, \r \n \xHH escapes.
 * Lines starting with # are comments. Prints every command whose replies differ; exit code 1 if any did.
 */
public class RespDiff {

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("usage: java RespDiff.java hostA:port hostB:port commands.txt");
            System.exit(2);
        }
        List<String> lines = Files.readAllLines(Path.of(args[2]), StandardCharsets.UTF_8);
        try (Socket a = connect(args[0]); Socket b = connect(args[1])) {
            InputStream ia = new BufferedInputStream(a.getInputStream());
            InputStream ib = new BufferedInputStream(b.getInputStream());
            int diffs = 0;
            int n = 0;
            for (String line : lines) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                byte[] cmd = encode(split(line));
                n++;
                String ra = roundTrip(a.getOutputStream(), ia, cmd);
                String rb = roundTrip(b.getOutputStream(), ib, cmd);
                if (!ra.equals(rb)) {
                    diffs++;
                    System.out.println("DIFF  " + line);
                    System.out.println("   A: " + show(ra));
                    System.out.println("   B: " + show(rb));
                }
            }
            System.out.println(n + " commands, " + diffs + " differences");
            System.exit(diffs == 0 ? 0 : 1);
        }
    }

    static Socket connect(String hp) throws IOException {
        int i = hp.lastIndexOf(':');
        Socket s = new Socket(hp.substring(0, i), Integer.parseInt(hp.substring(i + 1)));
        s.setSoTimeout(5000);
        s.setTcpNoDelay(true);
        return s;
    }

    static String roundTrip(OutputStream out, InputStream in, byte[] cmd) throws IOException {
        out.write(cmd);
        out.flush();
        ByteArrayOutputStream reply = new ByteArrayOutputStream();
        readReply(in, reply);
        return reply.toString(StandardCharsets.ISO_8859_1);
    }

    static void readReply(InputStream in, ByteArrayOutputStream acc) throws IOException {
        String line = readLine(in, acc);
        char t = line.charAt(0);
        switch (t) {
            case '+', '-', ':' -> { }
            case '$' -> {
                int len = Integer.parseInt(line.substring(1));
                if (len >= 0) {
                    for (int i = 0; i < len + 2; i++) {
                        acc.write(in.read());
                    }
                }
            }
            case '*' -> {
                int n = Integer.parseInt(line.substring(1));
                for (int i = 0; i < n; i++) {
                    readReply(in, acc);
                }
            }
            default -> throw new IOException("unexpected reply type " + t);
        }
    }

    static String readLine(InputStream in, ByteArrayOutputStream acc) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            acc.write(c);
            if (c == '\r') {
                acc.write(in.read());
                break;
            }
            sb.append((char) c);
        }
        if (c == -1) {
            throw new IOException("connection closed");
        }
        return sb.toString();
    }

    static List<byte[]> split(String line) {
        List<byte[]> out = new ArrayList<>();
        ByteArrayOutputStream cur = null;
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (!q && ch == ' ') {
                if (cur != null) {
                    out.add(cur.toByteArray());
                    cur = null;
                }
                continue;
            }
            if (cur == null) {
                cur = new ByteArrayOutputStream();
            }
            if (ch == '"') {
                q = !q;
            } else if (ch == '\\' && i + 1 < line.length()) {
                char e = line.charAt(++i);
                switch (e) {
                    case 'r' -> cur.write('\r');
                    case 'n' -> cur.write('\n');
                    case 'x' -> {
                        cur.write(Integer.parseInt(line.substring(i + 1, i + 3), 16));
                        i += 2;
                    }
                    default -> cur.write(e);
                }
            } else {
                byte[] b = String.valueOf(ch).getBytes(StandardCharsets.UTF_8);
                cur.write(b, 0, b.length);
            }
        }
        if (cur != null) {
            out.add(cur.toByteArray());
        }
        return out;
    }

    static byte[] encode(List<byte[]> argv) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(("*" + argv.size() + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (byte[] a : argv) {
            o.writeBytes(("$" + a.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            o.writeBytes(a);
            o.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        return o.toByteArray();
    }

    static String show(String raw) {
        return raw.replace("\r", "\\r").replace("\n", "\\n");
    }
}
