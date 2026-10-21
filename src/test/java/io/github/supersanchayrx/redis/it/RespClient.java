package io.github.supersanchayrx.redis.it;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** A minimal blocking RESP client for tests. Replies come back as raw ISO-8859-1 text. */
final class RespClient implements AutoCloseable {

    final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    RespClient(String host, int port) throws IOException {
        this(host, port, -1);
    }

    /** @param receiveBuffer SO_RCVBUF to set before connecting, or -1 for the default */
    RespClient(String host, int port, int receiveBuffer) throws IOException {
        socket = new Socket();
        if (receiveBuffer > 0) {
            socket.setReceiveBufferSize(receiveBuffer);
        }
        socket.connect(new InetSocketAddress(host, port), 5000);
        socket.setSoTimeout(10_000);
        socket.setTcpNoDelay(true);
        in = new BufferedInputStream(socket.getInputStream());
        out = socket.getOutputStream();
    }

    static byte[] encode(String... argv) {
        StringBuilder sb = new StringBuilder("*" + argv.length + "\r\n");
        for (String a : argv) {
            sb.append('$').append(a.getBytes(StandardCharsets.UTF_8).length).append("\r\n").append(a).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    void send(String... argv) throws IOException {
        sendRaw(encode(argv));
    }

    void sendRaw(byte[] bytes) throws IOException {
        out.write(bytes);
        out.flush();
    }

    String call(String... argv) throws IOException {
        send(argv);
        return read();
    }

    /** Reads exactly one complete reply. */
    String read() throws IOException {
        ByteArrayOutputStream acc = new ByteArrayOutputStream();
        readReply(acc);
        return acc.toString(StandardCharsets.ISO_8859_1);
    }

    /** True once the server has closed the connection (read returns end of stream). */
    boolean closedByServer() throws IOException {
        try {
            return in.read() == -1;
        } catch (java.net.SocketException e) {
            return true; // reset
        }
    }

    private void readReply(ByteArrayOutputStream acc) throws IOException {
        String line = readLine(acc);
        switch (line.charAt(0)) {
            case '+', '-', ':' -> { }
            case '$' -> {
                int len = Integer.parseInt(line.substring(1));
                if (len >= 0) {
                    byte[] body = in.readNBytes(len + 2);
                    acc.write(body);
                }
            }
            case '*' -> {
                int n = Integer.parseInt(line.substring(1));
                for (int i = 0; i < n; i++) {
                    readReply(acc);
                }
            }
            default -> throw new IOException("bad reply: " + line);
        }
    }

    private String readLine(ByteArrayOutputStream acc) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = in.read();
            if (c == -1) {
                throw new IOException("connection closed");
            }
            acc.write(c);
            if (c == '\r') {
                acc.write(in.read());
                return sb.toString();
            }
            sb.append((char) c);
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
