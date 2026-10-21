package io.github.supersanchayrx.redis.server;

import io.github.supersanchayrx.redis.protocol.Resp;
import io.github.supersanchayrx.redis.protocol.RespParser;
import io.github.supersanchayrx.redis.store.Key;

import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.LinkedHashSet;

/** One connection's state: socket, parser (with its input buffer), output queue, flags and subscriptions. */
public final class Client {

    public final long id;
    final SocketChannel channel;
    SelectionKey key;
    public final String addr;

    public final RespParser parser = new RespParser();
    /** Replies not yet written to the socket. Commands append here; the loop writes it before sleeping. */
    public final Resp out = new Resp();

    /** QUIT or a protocol error: send what's queued, then close. Nothing more is read. */
    boolean closeAfterReply;
    /** Scheduled for closing at the next safe point (output limit exceeded). */
    boolean closeAsap;
    String closeReason;
    boolean closed;
    /** Already in the loop's pending-writes queue. */
    boolean pendingWrite;

    public final LinkedHashSet<Key> channels = new LinkedHashSet<>();
    public final LinkedHashSet<Key> patterns = new LinkedHashSet<>();
    /** nanoTime when the output first went over the pub/sub soft limit; 0 when under it. */
    public long softLimitSince;

    public String name = "";
    final long createdNanos;

    Client(long id, SocketChannel channel, String addr, long nowNanos) {
        this.id = id;
        this.channel = channel;
        this.addr = addr;
        this.createdNanos = nowNanos;
    }

    /** A client with no socket, used to replay the AOF through the normal dispatcher. */
    public static Client fake() {
        return new Client(0, null, "aof", 0);
    }

    public int subscriptionCount() {
        return channels.size() + patterns.size();
    }

    /** RESP2 subscribed mode: only (P)SUBSCRIBE, (P)UNSUBSCRIBE, PING, QUIT and RESET are allowed. */
    public boolean isSubscribed() {
        return !channels.isEmpty() || !patterns.isEmpty();
    }

    public void closeAfterReply() {
        closeAfterReply = true;
    }

    public boolean isClosing() {
        return closed || closeAsap || closeAfterReply;
    }

    @Override
    public String toString() {
        return "id=" + id + " addr=" + addr;
    }
}
