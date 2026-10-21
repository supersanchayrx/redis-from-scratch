package io.github.supersanchayrx.redis.persist;

import io.github.supersanchayrx.redis.ds.LinkedList;
import io.github.supersanchayrx.redis.ds.SkipList;
import io.github.supersanchayrx.redis.protocol.Resp;
import io.github.supersanchayrx.redis.store.HashValue;
import io.github.supersanchayrx.redis.store.Key;
import io.github.supersanchayrx.redis.store.Keyspace;
import io.github.supersanchayrx.redis.store.ListValue;
import io.github.supersanchayrx.redis.store.StringValue;
import io.github.supersanchayrx.redis.store.Value;
import io.github.supersanchayrx.redis.store.ZSetValue;
import io.github.supersanchayrx.redis.util.Bytes;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Writes the smallest command list that rebuilds the current keyspace: one SET, RPUSH, HSET or ZADD per key
 * (split every 64 elements, Redis's AOF_REWRITE_ITEMS_PER_CMD) plus a PEXPIREAT for keys with a TTL.
 *
 * <p>This runs on the loop thread and blocks every client while it does. Redis rewrites in a forked child,
 * relying on copy-on-write memory for a consistent snapshot; the JVM can't fork, so a background version
 * would need a snapshot of its own design.
 */
final class AofRewriter {

    static final int ITEMS_PER_CMD = 64;
    private static final int FLUSH_AT = 1 << 20;

    private static final byte[] SET = Bytes.utf8("SET");
    private static final byte[] RPUSH = Bytes.utf8("RPUSH");
    private static final byte[] HSET = Bytes.utf8("HSET");
    private static final byte[] ZADD = Bytes.utf8("ZADD");
    private static final byte[] PEXPIREAT = Bytes.utf8("PEXPIREAT");

    private AofRewriter() {
    }

    /** Writes the snapshot to {@code tmp} and fsyncs it. Returns the number of keys written. */
    static long write(Keyspace ks, Path tmp) throws IOException {
        long[] keys = {0};
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            Resp buf = new Resp(FLUSH_AT + 64 * 1024);
            IOException[] failure = {null};
            ks.forEachLive((key, value) -> {
                if (failure[0] != null) {
                    return;
                }
                emit(buf, key, value);
                long at = ks.expireAt(key);
                if (at >= 0) {
                    buf.command(PEXPIREAT, key.bytes(), Bytes.ascii(at));
                }
                keys[0]++;
                if (buf.pending() >= FLUSH_AT) {
                    try {
                        drain(ch, buf);
                    } catch (IOException e) {
                        failure[0] = e;
                    }
                }
            });
            if (failure[0] != null) {
                throw failure[0];
            }
            drain(ch, buf);
            ch.force(true);
        }
        return keys[0];
    }

    private static void emit(Resp buf, Key key, Value value) {
        switch (value) {
            case StringValue s -> buf.command(SET, key.bytes(), s.bytes());
            case ListValue l -> {
                List<byte[]> batch = new ArrayList<>(ITEMS_PER_CMD);
                for (LinkedList.Node<byte[]> n = l.list.head(); n != null; n = n.next()) {
                    batch.add(n.value());
                    if (batch.size() == ITEMS_PER_CMD) {
                        emitBatch(buf, RPUSH, key, batch);
                    }
                }
                emitBatch(buf, RPUSH, key, batch);
            }
            case HashValue h -> {
                List<byte[]> batch = new ArrayList<>(ITEMS_PER_CMD * 2);
                for (Map.Entry<Key, byte[]> e : h.map.entrySet()) {
                    batch.add(e.getKey().bytes());
                    batch.add(e.getValue());
                    if (batch.size() == ITEMS_PER_CMD * 2) {
                        emitBatch(buf, HSET, key, batch);
                    }
                }
                emitBatch(buf, HSET, key, batch);
            }
            case ZSetValue z -> {
                List<byte[]> batch = new ArrayList<>(ITEMS_PER_CMD * 2);
                for (SkipList.Node n = z.list.first(); n != null; n = n.next()) {
                    // The shortest round-trip text reads back to the identical double.
                    batch.add(Bytes.formatDoubleBytes(n.score()));
                    batch.add(n.member().bytes());
                    if (batch.size() == ITEMS_PER_CMD * 2) {
                        emitBatch(buf, ZADD, key, batch);
                    }
                }
                emitBatch(buf, ZADD, key, batch);
            }
        }
    }

    private static void emitBatch(Resp buf, byte[] cmd, Key key, List<byte[]> items) {
        if (items.isEmpty()) {
            return;
        }
        buf.arrayHeader(items.size() + 2).bulk(cmd).bulk(key.bytes());
        for (byte[] item : items) {
            buf.bulk(item);
        }
        items.clear();
    }

    private static void drain(FileChannel ch, Resp buf) throws IOException {
        ByteBuffer bb = buf.pendingView();
        while (bb.hasRemaining()) {
            ch.write(bb);
        }
        buf.clear();
    }
}
