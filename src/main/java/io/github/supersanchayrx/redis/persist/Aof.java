package io.github.supersanchayrx.redis.persist;

import io.github.supersanchayrx.redis.Clock;
import io.github.supersanchayrx.redis.Config;
import io.github.supersanchayrx.redis.protocol.Resp;
import io.github.supersanchayrx.redis.store.Keyspace;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The append-only file. Write commands are appended, in their effect form, to an in-memory buffer during
 * an iteration; {@link #flush()} writes the whole buffer in before-sleep, before any reply goes out.
 * Under {@code appendfsync always} it also fsyncs there, so one fsync covers every write in the iteration
 * (group commit) and no client is told OK for a write that isn't on disk.
 */
public final class Aof {

    private static final Logger LOG = Logger.getLogger("aof");

    public static final String FILE_NAME = "appendonly.aof";

    private final Config.Fsync policy;
    private final Path path;
    private final boolean enabled;
    private final Clock clock;
    private final Resp buffer = new Resp(64 * 1024);

    private FileChannel channel;
    private boolean unsynced;
    private long lastWriteErrorLog;

    private final boolean useBackgroundFsync;
    private BackgroundFsync background;

    private long fsyncs;
    private long delayedFsyncs;
    private long bytesWritten;
    private long longestFsyncNanos;

    public Aof(Config config, Clock clock) {
        this.policy = config.appendfsync;
        this.path = config.dir.resolve(FILE_NAME);
        this.enabled = config.appendonly;
        this.clock = clock;
        this.useBackgroundFsync = config.aofFsyncThread && policy == Config.Fsync.EVERYSEC;
    }

    public boolean enabled() {
        return enabled;
    }

    public Path path() {
        return path;
    }

    public Config.Fsync policy() {
        return policy;
    }

    /** Opens the file for appending. Called after the load, so replayed commands are never re-appended. */
    public void open() throws IOException {
        if (!enabled) {
            return;
        }
        Files.createDirectories(path.toAbsolutePath().getParent());
        channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        if (useBackgroundFsync && background == null) {
            background = new BackgroundFsync(clock);
        }
    }

    public boolean isOpen() {
        return channel != null;
    }

    /** Queues one command. A no-op with appendonly off, and while loading (the file isn't open yet). */
    public void append(byte[][] argv) {
        if (channel != null) {
            buffer.command(argv);
        }
    }

    /** Writes the buffer to the file, and fsyncs under "always". Runs before replies are written. */
    public void flush() {
        if (channel == null || buffer.isEmpty()) {
            return;
        }
        try {
            ByteBuffer bb = buffer.pendingView();
            int n = bb.remaining();
            while (bb.hasRemaining()) {
                channel.write(bb);
            }
            bytesWritten += n;
            buffer.clear();
            if (policy == Config.Fsync.ALWAYS) {
                fsync();
            } else {
                unsynced = true;
            }
        } catch (IOException e) {
            writeFailed(e);
        }
    }

    /**
     * The everysec timer: one fsync a second if anything was written since the last one. With the background
     * thread, the loop only hands the fsync off; if the previous one is still running (a slow disk), this
     * tick is skipped and counted, like Redis's aof_delayed_fsync.
     */
    public void fsyncIfNeeded() {
        if (channel == null || !unsynced) {
            return;
        }
        if (background != null) {
            if (background.submit(channel)) {
                unsynced = false;
            } else {
                delayedFsyncs++;
            }
            return;
        }
        try {
            fsync();
        } catch (IOException e) {
            writeFailed(e);
        }
    }

    /**
     * Redis's bio fsync thread. The one thread besides the loop that the design allows: it only ever calls
     * force() on the file channel and never touches the keyspace. FileChannel is safe for a concurrent write
     * and force; a force on a channel the loop has since closed (rewrite, shutdown) just fails harmlessly.
     */
    private static final class BackgroundFsync implements Runnable {
        private final Object lock = new Object();
        private final Clock clock;
        private final Thread thread;
        private FileChannel pending;
        private boolean busy;
        private boolean stop;
        private volatile long count;
        private volatile long longestNanos;

        BackgroundFsync(Clock clock) {
            this.clock = clock;
            this.thread = new Thread(this, "aof-fsync");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        /** Returns false if the previous fsync hasn't finished. */
        boolean submit(FileChannel ch) {
            synchronized (lock) {
                if (busy) {
                    return false;
                }
                busy = true;
                pending = ch;
                lock.notifyAll();
                return true;
            }
        }

        @Override
        public void run() {
            while (true) {
                FileChannel ch;
                synchronized (lock) {
                    while (pending == null && !stop) {
                        try {
                            lock.wait();
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    if (pending == null) {
                        return;
                    }
                    ch = pending;
                    pending = null;
                }
                long start = clock.nanos();
                try {
                    ch.force(false);
                    count++;
                } catch (IOException e) {
                    // Closed by a rewrite or shutdown, which fsync on their own.
                }
                longestNanos = Math.max(longestNanos, clock.nanos() - start);
                synchronized (lock) {
                    busy = false;
                }
            }
        }

        /** Never interrupt: an interrupt during force() would close the file channel. */
        void shutdown() {
            synchronized (lock) {
                stop = true;
                lock.notifyAll();
            }
            try {
                thread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void fsync() throws IOException {
        long start = clock.nanos();
        // force(false) skips file metadata: the closest Java gets to fdatasync.
        channel.force(false);
        longestFsyncNanos = Math.max(longestFsyncNanos, clock.nanos() - start);
        fsyncs++;
        unsynced = false;
    }

    private void writeFailed(IOException e) {
        if (policy == Config.Fsync.ALWAYS) {
            // Clients were promised durability; carrying on would break that promise. Redis exits here too.
            LOG.log(Level.SEVERE, "Can't write the AOF with appendfsync always, exiting: " + e.getMessage());
            Runtime.getRuntime().halt(1);
        }
        long now = clock.millis();
        if (now - lastWriteErrorLog > 1000) {
            lastWriteErrorLog = now;
            LOG.log(Level.WARNING, "AOF write failed, will retry: " + e.getMessage());
        }
    }

    public record RewriteResult(long keys, long bytesBefore, long bytesAfter, long nanos) {
    }

    /**
     * BGREWRITEAOF, blocking: rewrites the file from the live keyspace into a temp file, fsyncs it, and
     * atomically renames it over the old one. Everything buffered so far goes to the old file first, so a
     * crash at any point leaves either the complete old file or the complete new one.
     */
    public RewriteResult rewrite(Keyspace ks) throws IOException {
        if (channel == null) {
            throw new IOException("the AOF is off");
        }
        long start = clock.nanos();
        flush();
        channel.force(false);
        long before = channel.size();
        Path tmp = path.resolveSibling(FILE_NAME + ".rewrite.tmp");
        long keys;
        try {
            keys = AofRewriter.write(ks, tmp);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        // Windows can't rename over a file that's open, so close first; reopen whatever is in place after.
        channel.close();
        channel = null;
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            open();
        }
        fsyncDirectory();
        unsynced = false;
        long nanos = clock.nanos() - start;
        long after = channel.size();
        LOG.info(() -> String.format("AOF rewritten: %d keys, %d -> %d bytes, loop blocked %.1f ms",
                keys, before, after, nanos / 1e6));
        return new RewriteResult(keys, before, after, nanos);
    }

    /** Makes the rename durable where the OS allows it (Linux); a no-op on Windows. */
    private void fsyncDirectory() {
        try (FileChannel dirChannel = FileChannel.open(path.toAbsolutePath().getParent(), StandardOpenOption.READ)) {
            dirChannel.force(true);
        } catch (IOException | RuntimeException ignored) {
            // not supported here
        }
    }

    /** Flush, fsync and close: the clean-shutdown path. */
    public void close() {
        if (channel == null) {
            return;
        }
        flush();
        if (background != null) {
            background.shutdown();
            background = null;
        }
        try {
            channel.force(false);
            channel.close();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "closing the AOF failed: " + e.getMessage());
        }
        channel = null;
    }

    public long fsyncs() {
        return fsyncs + (background == null ? 0 : background.count);
    }

    /** Ticks skipped because the background fsync of the previous second was still running. */
    public long delayedFsyncs() {
        return delayedFsyncs;
    }

    public boolean backgroundFsync() {
        return useBackgroundFsync;
    }

    public long bytesWritten() {
        return bytesWritten;
    }

    /** The longest single fsync, on whichever thread ran it. */
    public long longestFsyncNanos() {
        return Math.max(longestFsyncNanos, background == null ? 0 : background.longestNanos);
    }

    public int buffered() {
        return buffer.pending();
    }
}
