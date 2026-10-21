package io.github.supersanchayrx.redis.server;

import io.github.supersanchayrx.redis.Clock;
import io.github.supersanchayrx.redis.protocol.ProtocolException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The single thread that accepts connections, reads requests, runs commands and writes replies for every
 * client. One iteration:
 * <ol>
 *   <li>select, with a timeout equal to the time until the next timer;</li>
 *   <li>accept, read and parse (running every complete command), write leftovers for OP_WRITE keys;</li>
 *   <li>run due timers (active expiry, everysec fsync, pub/sub soft limits);</li>
 *   <li>before sleep: flush the AOF <em>first</em>, then write every client's queued replies.</li>
 * </ol>
 * OP_WRITE interest is only set while a client has bytes left over from a partial write; a socket is
 * almost always writable, so leaving it on would make select return at once forever.
 */
public final class EventLoop {

    private static final Logger LOG = Logger.getLogger("server");

    /** What the loop calls into: command execution and the persistence hooks. */
    public interface Handler {
        void onCommand(Client c, byte[][] argv);

        void onClose(Client c);

        /** Runs before any reply of this iteration is written: the AOF flush (and fsync under "always"). */
        void beforeSleep();

        void onShutdown();
    }

    /** Max bytes written to one client per iteration, so one huge reply can't starve the others. */
    private static final long MAX_WRITE_PER_ITERATION = 1024 * 1024;

    private final Clock clock;
    private final Timers timers = new Timers();
    private Handler handler;

    private Selector selector;
    private ServerSocketChannel listener;

    private final LinkedHashSet<Client> clients = new LinkedHashSet<>();
    private final ArrayDeque<Client> pendingWrites = new ArrayDeque<>();
    private final List<Client> closeQueue = new ArrayList<>();
    private long nextClientId = 1;

    private volatile boolean stopRequested;
    private volatile boolean running;

    private long iterations;
    private long totalConnections;
    private long rejectedProtocol;
    private long longestIterationNanos;

    public EventLoop(Clock clock) {
        this.clock = clock;
    }

    public void setHandler(Handler handler) {
        this.handler = handler;
    }

    public Timers timers() {
        return timers;
    }

    public Clock clock() {
        return clock;
    }

    /** Opens and binds the listening socket. Done after the AOF has loaded, so no client sees half a keyspace. */
    public void bind(String host, int port) throws IOException {
        selector = Selector.open();
        listener = ServerSocketChannel.open();
        listener.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        listener.bind(new InetSocketAddress(host, port), 511);
        listener.configureBlocking(false);
        listener.register(selector, SelectionKey.OP_ACCEPT);
    }

    public int port() {
        return ((InetSocketAddress) listener.socket().getLocalSocketAddress()).getPort();
    }

    /** Runs until {@link #requestStop()}. */
    public void run() {
        running = true;
        try {
            while (!stopRequested) {
                iterate();
            }
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "event loop failed", e);
        } finally {
            shutdown();
            running = false;
        }
    }

    /** Safe from any thread: the shutdown hook and the SHUTDOWN command both use it. */
    public void requestStop() {
        stopRequested = true;
        if (selector != null) {
            selector.wakeup();
        }
    }

    public boolean isRunning() {
        return running;
    }

    private void iterate() throws IOException {
        long wait = timers.nanosUntilNext(clock.nanos());
        if (wait <= 0) {
            selector.selectNow();
        } else {
            long ms = Math.max(1, (wait + 999_999) / 1_000_000);
            selector.select(Math.min(ms, 1000));
        }
        iterations++;
        long busyStart = clock.nanos();

        Iterator<SelectionKey> it = selector.selectedKeys().iterator();
        while (it.hasNext()) {
            SelectionKey k = it.next();
            it.remove(); // the selector never clears this set itself
            if (!k.isValid()) {
                continue;
            }
            if (k.attachment() == null) {
                if (k.isAcceptable()) {
                    acceptAll();
                }
                continue;
            }
            Client c = (Client) k.attachment();
            try {
                if (k.isReadable()) {
                    read(c);
                }
                if (k.isValid() && k.isWritable()) {
                    writeReady(c);
                }
            } catch (IOException e) {
                // One client's failure (a reset connection) closes that client only.
                close(c, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }

        timers.runDue(clock.nanos());
        beforeSleep();
        // How long every client waited on this iteration: the number a blocking fsync or rewrite shows up in.
        longestIterationNanos = Math.max(longestIterationNanos, clock.nanos() - busyStart);
    }

    private void acceptAll() {
        while (true) {
            SocketChannel ch;
            try {
                ch = listener.accept();
                if (ch == null) {
                    return;
                }
                ch.configureBlocking(false);
                ch.setOption(StandardSocketOptions.TCP_NODELAY, true); // Redis does too (networking.c)
                ch.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "accept failed: " + e.getMessage());
                return;
            }
            String addr = describe(ch);
            Client c = new Client(nextClientId++, ch, addr, clock.nanos());
            try {
                c.key = ch.register(selector, SelectionKey.OP_READ, c);
            } catch (IOException e) {
                closeQuietly(ch);
                continue;
            }
            clients.add(c);
            totalConnections++;
            LOG.fine(() -> "Accepted " + addr + " (id=" + c.id + ")");
        }
    }

    /** One read per readable event, up to the buffer's free space (16 KB normally), then every complete command. */
    private void read(Client c) throws IOException {
        ByteBuffer dst = c.parser.readBuffer();
        int n = c.channel.read(dst);
        if (n < 0) {
            close(c, "closed by client");
            return;
        }
        c.parser.commitRead();
        if (n > 0) {
            processInput(c);
        }
    }

    /** Runs every complete command in the client's buffer; replies are queued, not written. */
    void processInput(Client c) {
        while (!c.isClosing()) {
            byte[][] argv;
            try {
                argv = c.parser.next();
            } catch (ProtocolException e) {
                rejectedProtocol++;
                LOG.info(() -> e.getMessage() + " from client " + c);
                c.out.error("ERR " + e.getMessage());
                c.closeAfterReply();
                break;
            }
            if (argv == null) {
                break;
            }
            handler.onCommand(c, argv);
        }
        if (c.closeAfterReply && c.key != null && c.key.isValid()) {
            c.key.interestOps(c.key.interestOps() & ~SelectionKey.OP_READ);
        }
        if (!c.out.isEmpty() || c.closeAfterReply) {
            queueWrite(c);
        }
    }

    /** Called when OP_WRITE fired: drain what's left from an earlier partial write. */
    private void writeReady(Client c) throws IOException {
        c.out.writeTo(c.channel, MAX_WRITE_PER_ITERATION);
        afterWrite(c);
    }

    private void afterWrite(Client c) {
        if (c.closed) {
            return;
        }
        if (c.out.isEmpty()) {
            if (c.closeAfterReply) {
                close(c, c.closeReason == null ? "closed after reply" : c.closeReason);
                return;
            }
            setWriteInterest(c, false);
        } else {
            setWriteInterest(c, true);
        }
    }

    private void setWriteInterest(Client c, boolean on) {
        SelectionKey k = c.key;
        if (k == null || !k.isValid()) {
            return;
        }
        int ops = k.interestOps();
        int want = on ? (ops | SelectionKey.OP_WRITE) : (ops & ~SelectionKey.OP_WRITE);
        if (want != ops) {
            k.interestOps(want);
        }
    }

    /** Marks a client as having replies to send this iteration. Also used by PUBLISH for its subscribers. */
    public void queueWrite(Client c) {
        if (!c.pendingWrite && !c.closed && c.channel != null) {
            c.pendingWrite = true;
            pendingWrites.add(c);
        }
    }

    private void beforeSleep() {
        // a. The AOF goes to disk before any client hears "OK" (Redis's beforeSleep has the same rule).
        handler.beforeSleep();
        processCloseQueue();
        // b. One write per client per iteration, however many commands it sent.
        Client c;
        while ((c = pendingWrites.poll()) != null) {
            c.pendingWrite = false;
            if (c.closed) {
                continue;
            }
            try {
                c.out.writeTo(c.channel, MAX_WRITE_PER_ITERATION);
            } catch (IOException e) {
                close(c, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                continue;
            }
            afterWrite(c);
        }
    }

    /** Closes a client at the next safe point: used where closing now would disturb an iteration in progress. */
    public void closeAsync(Client c, String reason) {
        if (c.closeAsap || c.closed) {
            return;
        }
        c.closeAsap = true;
        c.closeReason = reason;
        closeQueue.add(c);
    }

    private void processCloseQueue() {
        if (closeQueue.isEmpty()) {
            return;
        }
        for (Client c : closeQueue) {
            close(c, c.closeReason);
        }
        closeQueue.clear();
    }

    public void close(Client c, String reason) {
        if (c.closed) {
            return;
        }
        c.closed = true;
        handler.onClose(c);
        clients.remove(c);
        if (c.key != null) {
            c.key.cancel();
        }
        closeQuietly(c.channel);
        c.out.clear();
        LOG.fine(() -> "Client " + c + " closed: " + reason);
    }

    private void shutdown() {
        for (Client c : new ArrayList<>(clients)) {
            close(c, "server shutting down");
        }
        try {
            handler.onShutdown();
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "shutdown hook failed", e);
        }
        try {
            if (listener != null) {
                listener.close();
            }
            if (selector != null) {
                selector.close();
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "closing the listener failed", e);
        }
    }

    public Collection<Client> clients() {
        return Collections.unmodifiableCollection(clients);
    }

    public long iterations() {
        return iterations;
    }

    public long totalConnections() {
        return totalConnections;
    }

    public long rejectedProtocol() {
        return rejectedProtocol;
    }

    public long longestIterationNanos() {
        return longestIterationNanos;
    }

    /** CONFIG RESETSTAT. */
    public void resetStats() {
        longestIterationNanos = 0;
    }

    private static String describe(SocketChannel ch) {
        try {
            InetSocketAddress a = (InetSocketAddress) ch.getRemoteAddress();
            return a.getAddress().getHostAddress() + ":" + a.getPort();
        } catch (IOException | RuntimeException e) {
            return "?";
        }
    }

    private static void closeQuietly(SocketChannel ch) {
        if (ch == null) {
            return;
        }
        try {
            ch.close();
        } catch (IOException ignored) {
            // already gone
        }
    }
}
