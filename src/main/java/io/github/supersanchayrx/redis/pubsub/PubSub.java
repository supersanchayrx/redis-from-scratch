package io.github.supersanchayrx.redis.pubsub;

import io.github.supersanchayrx.redis.Config;
import io.github.supersanchayrx.redis.protocol.Resp;
import io.github.supersanchayrx.redis.server.Client;
import io.github.supersanchayrx.redis.server.EventLoop;
import io.github.supersanchayrx.redis.store.Key;
import io.github.supersanchayrx.redis.util.Bytes;
import io.github.supersanchayrx.redis.util.Glob;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Channel and pattern subscriptions. Each channel maps to its subscribers in subscription order, and each
 * client keeps its own channel set, so a disconnect is removed everywhere in O(its subscriptions).
 *
 * <p>PUBLISH never writes to a socket: it appends the encoded message (encoded once, shared by every
 * subscriber) to each output queue, and the loop writes it before sleeping like any other reply.
 */
public final class PubSub {

    private static final Logger LOG = Logger.getLogger("pubsub");

    private static final byte[] MESSAGE = Bytes.utf8("message");
    private static final byte[] PMESSAGE = Bytes.utf8("pmessage");

    private final HashMap<Key, LinkedHashSet<Client>> channels = new HashMap<>();
    private final HashMap<Key, LinkedHashSet<Client>> patterns = new HashMap<>();
    private final Config config;
    private final EventLoop loop;

    private long hardLimitDisconnects;
    private long softLimitDisconnects;

    public PubSub(Config config, EventLoop loop) {
        this.config = config;
        this.loop = loop;
    }

    /** Returns true if the client wasn't already subscribed. */
    public boolean subscribe(Client c, Key channel) {
        if (!c.channels.add(channel)) {
            return false;
        }
        channels.computeIfAbsent(channel, k -> new LinkedHashSet<>()).add(c);
        return true;
    }

    public boolean unsubscribe(Client c, Key channel) {
        if (!c.channels.remove(channel)) {
            return false;
        }
        removeFrom(channels, channel, c);
        return true;
    }

    public boolean psubscribe(Client c, Key pattern) {
        if (!c.patterns.add(pattern)) {
            return false;
        }
        patterns.computeIfAbsent(pattern, k -> new LinkedHashSet<>()).add(c);
        return true;
    }

    public boolean punsubscribe(Client c, Key pattern) {
        if (!c.patterns.remove(pattern)) {
            return false;
        }
        removeFrom(patterns, pattern, c);
        return true;
    }

    /** Drops every subscription of a client that is going away. */
    public void removeClient(Client c) {
        for (Key ch : c.channels) {
            removeFrom(channels, ch, c);
        }
        for (Key p : c.patterns) {
            removeFrom(patterns, p, c);
        }
        c.channels.clear();
        c.patterns.clear();
    }

    private static void removeFrom(Map<Key, LinkedHashSet<Client>> index, Key name, Client c) {
        LinkedHashSet<Client> subs = index.get(name);
        if (subs != null) {
            subs.remove(c);
            if (subs.isEmpty()) {
                index.remove(name); // no empty channels left behind
            }
        }
    }

    /** Delivers to channel subscribers and matching pattern subscribers; returns how many received it. */
    public int publish(Key channel, byte[] message) {
        int receivers = 0;
        LinkedHashSet<Client> subs = channels.get(channel);
        if (subs != null) {
            byte[] encoded = new Resp(32 + channel.length() + message.length)
                    .arrayHeader(3).bulk(MESSAGE).bulk(channel.bytes()).bulk(message).toByteArray();
            for (Client s : subs) {
                deliver(s, encoded);
                receivers++;
            }
        }
        if (!patterns.isEmpty()) {
            for (Map.Entry<Key, LinkedHashSet<Client>> e : patterns.entrySet()) {
                if (!Glob.match(e.getKey().bytes(), channel.bytes())) {
                    continue;
                }
                byte[] encoded = new Resp(48 + e.getKey().length() + channel.length() + message.length)
                        .arrayHeader(4).bulk(PMESSAGE).bulk(e.getKey().bytes()).bulk(channel.bytes()).bulk(message)
                        .toByteArray();
                for (Client s : e.getValue()) {
                    deliver(s, encoded);
                    receivers++;
                }
            }
        }
        return receivers;
    }

    private void deliver(Client s, byte[] encoded) {
        if (s.isClosing()) {
            return; // already being cut off: don't keep growing its queue
        }
        s.out.raw(encoded);
        loop.queueWrite(s);
        long pending = s.out.pending();
        if (config.pubsubHardLimit > 0 && pending > config.pubsubHardLimit) {
            hardLimitDisconnects++;
            LOG.warning(() -> "Client " + s + " scheduled to be closed ASAP for overcoming of output buffer limits"
                    + " (hard limit, " + pending + " bytes pending)");
            loop.closeAsync(s, "pubsub output over the hard limit");
        }
    }

    /**
     * The once-a-second soft-limit check: a subscriber continuously over the soft limit for longer than the
     * configured seconds is disconnected.
     */
    public void checkSoftLimits(long nowNanos) {
        if (config.pubsubSoftLimit <= 0) {
            return;
        }
        List<Client> over = null;
        for (Client c : loop.clients()) {
            if (!c.isSubscribed()) {
                continue;
            }
            if (c.out.pending() > config.pubsubSoftLimit) {
                if (c.softLimitSince == 0) {
                    c.softLimitSince = nowNanos;
                } else if (nowNanos - c.softLimitSince > config.pubsubSoftSeconds * 1_000_000_000L) {
                    if (over == null) {
                        over = new ArrayList<>();
                    }
                    over.add(c);
                }
            } else {
                c.softLimitSince = 0;
            }
        }
        if (over != null) {
            for (Client c : over) {
                softLimitDisconnects++;
                LOG.warning(() -> "Client " + c + " scheduled to be closed ASAP for overcoming of output buffer limits"
                        + " (soft limit for " + config.pubsubSoftSeconds + " s, " + c.out.pending() + " bytes pending)");
                loop.closeAsync(c, "pubsub output over the soft limit");
            }
        }
    }

    public int channelCount() {
        return channels.size();
    }

    public int patternCount() {
        return patterns.size();
    }

    public int subscriberCount(Key channel) {
        LinkedHashSet<Client> subs = channels.get(channel);
        return subs == null ? 0 : subs.size();
    }

    /** Active channels (with at least one subscriber) matching {@code pattern}, or all if null. */
    public List<Key> activeChannels(byte[] pattern) {
        List<Key> out = new ArrayList<>();
        for (Key k : channels.keySet()) {
            if (pattern == null || Glob.match(pattern, k.bytes())) {
                out.add(k);
            }
        }
        return out;
    }

    public long hardLimitDisconnects() {
        return hardLimitDisconnects;
    }

    public long softLimitDisconnects() {
        return softLimitDisconnects;
    }
}
