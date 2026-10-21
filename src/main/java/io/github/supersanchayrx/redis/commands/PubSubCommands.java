package io.github.supersanchayrx.redis.commands;

import io.github.supersanchayrx.redis.server.Client;
import io.github.supersanchayrx.redis.store.Key;
import io.github.supersanchayrx.redis.util.Bytes;

import java.util.ArrayList;
import java.util.List;

import static io.github.supersanchayrx.redis.commands.CommandSpec.FAST;
import static io.github.supersanchayrx.redis.commands.CommandSpec.PUBSUB;

/**
 * SUBSCRIBE, UNSUBSCRIBE, PSUBSCRIBE, PUNSUBSCRIBE, PUBLISH, PUBSUB, RESET. Messages are fire-and-forget:
 * nothing here is a write, so nothing reaches the AOF.
 */
final class PubSubCommands {

    private static final byte[] SUBSCRIBE = Bytes.utf8("subscribe");
    private static final byte[] UNSUBSCRIBE = Bytes.utf8("unsubscribe");
    private static final byte[] PSUBSCRIBE = Bytes.utf8("psubscribe");
    private static final byte[] PUNSUBSCRIBE = Bytes.utf8("punsubscribe");

    private PubSubCommands() {
    }

    static void register(CommandTable t) {
        t.add("SUBSCRIBE", -2, PUBSUB, PubSubCommands::subscribe);
        t.add("UNSUBSCRIBE", -1, PUBSUB, PubSubCommands::unsubscribe);
        t.add("PSUBSCRIBE", -2, PUBSUB, PubSubCommands::psubscribe);
        t.add("PUNSUBSCRIBE", -1, PUBSUB, PubSubCommands::punsubscribe);
        t.add("PUBLISH", 3, FAST, PubSubCommands::publish);
        t.add("PUBSUB", -2, 0, PubSubCommands::pubsub);
        t.add("RESET", 1, PUBSUB | FAST, PubSubCommands::reset);
    }

    /** One reply per channel: ["subscribe", channel, total subscriptions]. */
    private static void subscribe(Context ctx, Client c, byte[][] a) {
        for (int i = 1; i < a.length; i++) {
            ctx.pubsub.subscribe(c, Key.wrap(a[i]));
            reply(c, SUBSCRIBE, a[i]);
        }
    }

    private static void psubscribe(Context ctx, Client c, byte[][] a) {
        for (int i = 1; i < a.length; i++) {
            ctx.pubsub.psubscribe(c, Key.wrap(a[i]));
            reply(c, PSUBSCRIBE, a[i]);
        }
    }

    private static void unsubscribe(Context ctx, Client c, byte[][] a) {
        if (a.length == 1) {
            if (c.channels.isEmpty()) {
                reply(c, UNSUBSCRIBE, null);
                return;
            }
            for (Key ch : new ArrayList<>(c.channels)) {
                ctx.pubsub.unsubscribe(c, ch);
                reply(c, UNSUBSCRIBE, ch.bytes());
            }
            return;
        }
        for (int i = 1; i < a.length; i++) {
            ctx.pubsub.unsubscribe(c, Key.wrap(a[i]));
            reply(c, UNSUBSCRIBE, a[i]);
        }
    }

    private static void punsubscribe(Context ctx, Client c, byte[][] a) {
        if (a.length == 1) {
            if (c.patterns.isEmpty()) {
                reply(c, PUNSUBSCRIBE, null);
                return;
            }
            for (Key p : new ArrayList<>(c.patterns)) {
                ctx.pubsub.punsubscribe(c, p);
                reply(c, PUNSUBSCRIBE, p.bytes());
            }
            return;
        }
        for (int i = 1; i < a.length; i++) {
            ctx.pubsub.punsubscribe(c, Key.wrap(a[i]));
            reply(c, PUNSUBSCRIBE, a[i]);
        }
    }

    private static void reply(Client c, byte[] kind, byte[] name) {
        c.out.arrayHeader(3).bulk(kind).bulk(name).integer(c.subscriptionCount());
    }

    private static void publish(Context ctx, Client c, byte[][] a) {
        c.out.integer(ctx.pubsub.publish(Key.wrap(a[1]), a[2]));
    }

    private static void pubsub(Context ctx, Client c, byte[][] a) {
        String sub = Bytes.lower(a[1]);
        switch (sub) {
            case "channels" -> {
                if (a.length > 3) {
                    throw CommandException.wrongArgs("pubsub|channels");
                }
                List<Key> chans = ctx.pubsub.activeChannels(a.length == 3 ? a[2] : null);
                c.out.arrayHeader(chans.size());
                for (Key k : chans) {
                    c.out.bulk(k.bytes());
                }
            }
            case "numsub" -> {
                c.out.arrayHeader((a.length - 2) * 2);
                for (int i = 2; i < a.length; i++) {
                    c.out.bulk(a[i]).integer(ctx.pubsub.subscriberCount(Key.wrap(a[i])));
                }
            }
            case "numpat" -> {
                if (a.length != 2) {
                    throw CommandException.wrongArgs("pubsub|numpat");
                }
                c.out.integer(ctx.pubsub.patternCount());
            }
            default -> throw CommandException.unknownSubcommand(Bytes.str(a[1]), "PUBSUB");
        }
    }

    /** Drops every subscription (leaving subscribed mode) and the client name. */
    private static void reset(Context ctx, Client c, byte[][] a) {
        ctx.pubsub.removeClient(c);
        c.name = "";
        c.out.simple("RESET");
    }
}
