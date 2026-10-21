package io.github.supersanchayrx.redis.commands;

import io.github.supersanchayrx.redis.protocol.Resp;
import io.github.supersanchayrx.redis.server.Client;
import io.github.supersanchayrx.redis.util.Bytes;
import io.github.supersanchayrx.redis.util.Glob;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

import static io.github.supersanchayrx.redis.commands.CommandSpec.FAST;
import static io.github.supersanchayrx.redis.commands.CommandSpec.PUBSUB;
import static io.github.supersanchayrx.redis.commands.CommandSpec.READONLY;
import static io.github.supersanchayrx.redis.commands.CommandSpec.WRITE;

/** PING, ECHO, QUIT, COMMAND, CONFIG, DBSIZE, FLUSHALL, SHUTDOWN, INFO, SELECT, TIME, CLIENT. */
final class ServerCommands {

    private static final Logger LOG = Logger.getLogger("server");

    private static final byte[] PONG_LOWER = Bytes.utf8("pong");
    private static final byte[] EMPTY = new byte[0];

    private ServerCommands() {
    }

    static void register(CommandTable t) {
        t.add("PING", -1, FAST | PUBSUB, ServerCommands::ping);
        t.add("ECHO", 2, FAST, (ctx, c, a) -> c.out.bulk(a[1]));
        t.add("QUIT", -1, FAST | PUBSUB, (ctx, c, a) -> {
            c.out.raw(Resp.OK);
            c.closeAfterReply();
        });
        t.add("COMMAND", -1, 0, ServerCommands::command);
        t.add("CONFIG", -2, 0, ServerCommands::config);
        t.add("DBSIZE", 1, READONLY | FAST, (ctx, c, a) -> c.out.integer(ctx.ks.size()));
        t.add("FLUSHALL", -1, WRITE, ServerCommands::flush);
        t.add("FLUSHDB", -1, WRITE, ServerCommands::flush);
        t.add("SHUTDOWN", -1, 0, ServerCommands::shutdown);
        t.add("INFO", -1, 0, ServerCommands::info);
        t.add("SELECT", 2, FAST, ServerCommands::select);
        t.add("TIME", 1, FAST, ServerCommands::time);
        t.add("CLIENT", -2, 0, ServerCommands::client);
    }

    private static void ping(Context ctx, Client c, byte[][] a) {
        if (a.length > 2) {
            throw CommandException.wrongArgs("ping");
        }
        if (c.isSubscribed()) {
            // In RESP2 subscribed mode PING answers in the push format.
            c.out.arrayHeader(2).bulk(PONG_LOWER).bulk(a.length == 2 ? a[1] : EMPTY);
        } else if (a.length == 2) {
            c.out.bulk(a[1]);
        } else {
            c.out.raw(Resp.PONG);
        }
    }

    /**
     * redis-cli's interactive mode sends COMMAND DOCS to build its hints; on an error it falls back to its
     * built-in help table, so an error reply is the compatible answer here.
     */
    private static void command(Context ctx, Client c, byte[][] a) {
        if (a.length == 2 && Args.eq(a[1], "COUNT")) {
            c.out.integer(ctx.table.size());
            return;
        }
        if (a.length == 1) {
            throw new CommandException("ERR COMMAND without a subcommand is not supported by this server");
        }
        throw CommandException.unknownSubcommand(Bytes.str(a[1]), "COMMAND");
    }

    private static void config(Context ctx, Client c, byte[][] a) {
        String sub = Bytes.lower(a[1]);
        switch (sub) {
            case "get" -> {
                if (a.length < 3) {
                    throw CommandException.wrongArgs("config|get");
                }
                // CONFIG GET takes several glob patterns (Redis 7+) and replies with a flat name/value array.
                Map<String, String> all = ctx.config.asMap();
                List<String> hits = new ArrayList<>();
                for (Map.Entry<String, String> e : all.entrySet()) {
                    byte[] name = Bytes.utf8(e.getKey());
                    for (int i = 2; i < a.length; i++) {
                        if (Glob.matchNoCase(a[i], name)) {
                            hits.add(e.getKey());
                            break;
                        }
                    }
                }
                c.out.arrayHeader(hits.size() * 2);
                for (String name : hits) {
                    c.out.bulk(name).bulk(all.get(name));
                }
            }
            case "resetstat" -> {
                if (a.length != 2) {
                    throw CommandException.wrongArgs("config|resetstat");
                }
                c.out.raw(Resp.OK);
            }
            case "set", "rewrite" -> throw new CommandException(
                    "ERR CONFIG " + sub.toUpperCase(Locale.ROOT) + " is not supported by this server; pass flags at startup");
            default -> throw CommandException.unknownSubcommand(Bytes.str(a[1]), "CONFIG");
        }
    }

    private static void flush(Context ctx, Client c, byte[][] a) {
        if (a.length > 2 || (a.length == 2 && !Args.eq(a[1], "ASYNC") && !Args.eq(a[1], "SYNC"))) {
            throw CommandException.SYNTAX;
        }
        ctx.ks.flush();
        c.out.raw(Resp.OK);
    }

    /** Stops the loop; the loop's shutdown path flushes and fsyncs the AOF. No reply, like Redis. */
    private static void shutdown(Context ctx, Client c, byte[][] a) {
        for (int i = 1; i < a.length; i++) {
            if (!Args.eq(a[i], "NOSAVE") && !Args.eq(a[i], "SAVE") && !Args.eq(a[i], "NOW")
                    && !Args.eq(a[i], "FORCE") && !Args.eq(a[i], "ABORT")) {
                throw CommandException.SYNTAX;
            }
        }
        LOG.warning("User requested shutdown...");
        ctx.loop.requestStop();
        c.closeAfterReply();
    }

    private static void select(Context ctx, Client c, byte[][] a) {
        // One database only (CONFIG GET databases says 1), so anything but 0 is out of range.
        if (Args.parseLong(a[1]) != 0) {
            throw new CommandException("ERR DB index is out of range");
        }
        c.out.raw(Resp.OK);
    }

    private static void time(Context ctx, Client c, byte[][] a) {
        long micros = ctx.clock.millis() * 1000;
        c.out.arrayHeader(2).bulk(micros / 1_000_000).bulk(micros % 1_000_000);
    }

    private static void client(Context ctx, Client c, byte[][] a) {
        String sub = Bytes.lower(a[1]);
        switch (sub) {
            case "setname" -> {
                if (a.length != 3) {
                    throw CommandException.wrongArgs("client|setname");
                }
                c.name = Bytes.str(a[2]);
                c.out.raw(Resp.OK);
            }
            case "getname" -> c.out.bulk(c.name.isEmpty() ? null : Bytes.utf8(c.name));
            case "id" -> c.out.integer(c.id);
            case "setinfo" -> c.out.raw(Resp.OK); // client libraries send lib-name/lib-ver; accept and ignore
            default -> throw CommandException.unknownSubcommand(Bytes.str(a[1]), "CLIENT");
        }
    }

    private static void info(Context ctx, Client c, byte[][] a) {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        long uptime = (ctx.clock.millis() - ctx.startMillis) / 1000;
        StringBuilder s = new StringBuilder(1024);
        s.append("# Server\r\n")
                .append("redis_version:0.1.0\r\n")
                .append("server_name:redis-from-scratch\r\n")
                .append("redis_mode:standalone\r\n")
                .append("multiplexing_api:java.nio\r\n")
                .append("java_version:").append(System.getProperty("java.version")).append("\r\n")
                .append("process_id:").append(ProcessHandle.current().pid()).append("\r\n")
                .append("tcp_port:").append(ctx.config.port).append("\r\n")
                .append("uptime_in_seconds:").append(uptime).append("\r\n")
                .append("hz:").append(ctx.config.hz).append("\r\n")
                .append("\r\n# Clients\r\n")
                .append("connected_clients:").append(ctx.loop.clients().size()).append("\r\n")
                .append("pubsub_clients:").append(ctx.loop.clients().stream().filter(Client::isSubscribed).count()).append("\r\n")
                .append("\r\n# Memory\r\n")
                .append("used_memory:").append(used).append("\r\n")
                .append("used_memory_human:").append(String.format(Locale.ROOT, "%.2fM", used / 1048576.0)).append("\r\n")
                .append("maxmemory:").append(rt.maxMemory()).append("\r\n")
                .append("gc:").append(ManagementFactory.getGarbageCollectorMXBeans().stream()
                        .map(b -> b.getName().replace(' ', '_') + "=" + b.getCollectionCount() + "/" + b.getCollectionTime() + "ms")
                        .reduce((x, y) -> x + "," + y).orElse("")).append("\r\n")
                .append("\r\n# Persistence\r\n")
                .append("loading:0\r\n")
                .append("aof_enabled:").append(ctx.aof.enabled() ? 1 : 0).append("\r\n")
                .append("aof_fsync_policy:").append(ctx.config.appendfsync.name().toLowerCase(Locale.ROOT)).append("\r\n")
                .append("aof_bytes_written:").append(ctx.aof.bytesWritten()).append("\r\n")
                .append("aof_fsyncs:").append(ctx.aof.fsyncs()).append("\r\n")
                .append("aof_longest_fsync_us:").append(ctx.aof.longestFsyncNanos() / 1000).append("\r\n")
                .append("\r\n# Stats\r\n")
                .append("total_connections_received:").append(ctx.loop.totalConnections()).append("\r\n")
                .append("total_commands_processed:").append(ctx.stats.commands).append("\r\n")
                .append("rejected_protocol_errors:").append(ctx.loop.rejectedProtocol()).append("\r\n")
                .append("expired_keys:").append(ctx.ks.expiredKeys()).append("\r\n")
                .append("event_loop_iterations:").append(ctx.loop.iterations()).append("\r\n")
                .append("pubsub_channels:").append(ctx.pubsub.channelCount()).append("\r\n")
                .append("pubsub_patterns:").append(ctx.pubsub.patternCount()).append("\r\n")
                .append("pubsub_hard_limit_disconnects:").append(ctx.pubsub.hardLimitDisconnects()).append("\r\n")
                .append("pubsub_soft_limit_disconnects:").append(ctx.pubsub.softLimitDisconnects()).append("\r\n");
        if (ctx.expiry != null) {
            s.append("expire_cycle_runs:").append(ctx.expiry.runs()).append("\r\n")
                    .append("expire_cycle_longest_us:").append(ctx.expiry.longestRunNanos() / 1000).append("\r\n")
                    .append("expire_cycle_time_limit_hits:").append(ctx.expiry.timeLimitHits()).append("\r\n");
        }
        s.append("\r\n# Keyspace\r\n");
        if (ctx.ks.size() > 0) {
            s.append("db0:keys=").append(ctx.ks.size()).append(",expires=").append(ctx.ks.volatileCount())
                    .append(",avg_ttl=0\r\n");
        }
        c.out.bulk(s.toString());
    }
}
