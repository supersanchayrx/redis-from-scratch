import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Crash-test writer: sends INCR counter one at a time (never more than one in flight) and remembers the
 * last value the server acknowledged, so after a kill -9 we know exactly which writes a client was told
 * succeeded. Prints "acked=N" when the connection drops or the time is up.
 * Usage: java CrashWriter.java <host> <port> <seconds>
 */
public class CrashWriter {

    public static void main(String[] args) throws Exception {
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        long until = System.nanoTime() + Long.parseLong(args[2]) * 1_000_000_000L;
        byte[] incr = "*2\r\n$4\r\nINCR\r\n$7\r\ncounter\r\n".getBytes(StandardCharsets.US_ASCII);
        long acked = 0;
        long sent = 0;
        try (Socket s = new Socket(host, port)) {
            s.setTcpNoDelay(true);
            OutputStream out = s.getOutputStream();
            InputStream in = new BufferedInputStream(s.getInputStream());
            while (System.nanoTime() < until) {
                out.write(incr);
                out.flush();
                sent++;
                acked = readInteger(in);
            }
        } catch (IOException e) {
            System.out.println("connection ended: " + e.getMessage());
        }
        System.out.println("sent=" + sent);
        System.out.println("acked=" + acked);
    }

    private static long readInteger(InputStream in) throws IOException {
        int c = in.read();
        if (c != ':') {
            throw new IOException("unexpected reply byte " + c);
        }
        long v = 0;
        boolean neg = false;
        while ((c = in.read()) != '\r') {
            if (c == -1) {
                throw new IOException("closed mid-reply");
            }
            if (c == '-') {
                neg = true;
            } else {
                v = v * 10 + (c - '0');
            }
        }
        if (in.read() != '\n') {
            throw new IOException("closed mid-reply");
        }
        return neg ? -v : v;
    }
}
