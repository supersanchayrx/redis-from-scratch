package io.github.supersanchayrx.redis;

import io.github.supersanchayrx.redis.commands.Context;
import io.github.supersanchayrx.redis.server.Client;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs commands straight through the dispatcher, without sockets, and returns the raw reply text.
 * Arguments are split on spaces; use {@link #runArgs} for arguments containing spaces or binary bytes.
 */
public final class Harness {

    public final Config config;
    public final FakeClock clock;
    public final Server server;
    public final Context ctx;
    private final Client client = Client.fake();

    public Harness() {
        this(new Config());
    }

    public Harness(Config config) {
        this(config, new FakeClock(1_800_000_000_000L));
    }

    public Harness(Config config, FakeClock clock) {
        this.config = config;
        this.clock = clock;
        this.server = new Server(config, clock);
        this.ctx = server.context();
    }

    /** Runs one command and returns its reply bytes as ISO-8859-1 text with CRLFs, e.g. "+OK\r\n". */
    public String run(String command) {
        String[] words = command.trim().split(" +");
        byte[][] argv = new byte[words.length][];
        for (int i = 0; i < words.length; i++) {
            argv[i] = words[i].getBytes(StandardCharsets.UTF_8);
        }
        return runArgs(argv);
    }

    public String runArgs(byte[]... argv) {
        ctx.table.execute(ctx, client, argv);
        String out = new String(client.out.toByteArray(), StandardCharsets.ISO_8859_1);
        client.out.clear();
        return out;
    }

    public List<String> runAll(String... commands) {
        List<String> out = new ArrayList<>();
        for (String c : commands) {
            out.add(run(c));
        }
        return out;
    }

    /** RESP for an array of bulk strings, for building expected replies. */
    public static String bulks(String... items) {
        StringBuilder sb = new StringBuilder("*" + items.length + "\r\n");
        for (String s : items) {
            if (s == null) {
                sb.append("$-1\r\n");
            } else {
                sb.append('$').append(s.getBytes(StandardCharsets.UTF_8).length).append("\r\n").append(s).append("\r\n");
            }
        }
        return sb.toString();
    }

    public static String bulk(String s) {
        return "$" + s.getBytes(StandardCharsets.UTF_8).length + "\r\n" + s + "\r\n";
    }
}
