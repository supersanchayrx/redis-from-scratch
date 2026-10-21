package io.github.supersanchayrx.redis.persist;

import io.github.supersanchayrx.redis.Clock;
import io.github.supersanchayrx.redis.Config;
import io.github.supersanchayrx.redis.protocol.Resp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private long fsyncs;
    private long bytesWritten;
    private long longestFsyncNanos;

    public Aof(Config config, Clock clock) {
        this.policy = config.appendfsync;
        this.path = config.dir.resolve(FILE_NAME);
        this.enabled = config.appendonly;
        this.clock = clock;
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

    /** The everysec timer: one fsync a second if anything was written since the last one. */
    public void fsyncIfNeeded() {
        if (channel == null || !unsynced) {
            return;
        }
        try {
            fsync();
        } catch (IOException e) {
            writeFailed(e);
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

    /** Flush, fsync and close: the clean-shutdown path. */
    public void close() {
        if (channel == null) {
            return;
        }
        flush();
        try {
            channel.force(false);
            channel.close();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "closing the AOF failed: " + e.getMessage());
        }
        channel = null;
    }

    public long fsyncs() {
        return fsyncs;
    }

    public long bytesWritten() {
        return bytesWritten;
    }

    public long longestFsyncNanos() {
        return longestFsyncNanos;
    }

    public int buffered() {
        return buffer.pending();
    }
}
