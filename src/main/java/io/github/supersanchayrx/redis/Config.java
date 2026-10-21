package io.github.supersanchayrx.redis;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Server configuration. Flag names mirror redis.conf, so {@code CONFIG GET} answers from the same values.
 */
public final class Config {

    public enum Fsync { ALWAYS, EVERYSEC, NO }

    public int port = 6380;
    public String bind = "0.0.0.0";
    public Path dir = Path.of(".");
    public boolean appendonly = false;
    public Fsync appendfsync = Fsync.EVERYSEC;
    public int hz = 10;
    public String loglevel = "notice";
    /** With appendfsync everysec, fsync on a background thread (like Redis's bio) instead of the loop. */
    public boolean aofFsyncThread = true;

    /** client-output-buffer-limit pubsub: hard bytes, soft bytes, soft seconds (Redis default 32mb 8mb 60). */
    public long pubsubHardLimit = 32L * 1024 * 1024;
    public long pubsubSoftLimit = 8L * 1024 * 1024;
    public int pubsubSoftSeconds = 60;

    public static Config parse(String[] args) {
        Config c = new Config();
        for (int i = 0; i < args.length; i++) {
            String flag = args[i];
            if (!flag.startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument '" + flag + "' (flags look like --port 6380)");
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("missing value for " + flag);
            }
            String value = args[++i];
            switch (flag.substring(2).toLowerCase(Locale.ROOT)) {
                case "port" -> c.port = parseInt(flag, value, 0, 65535);
                case "bind" -> c.bind = value;
                case "dir" -> c.dir = Path.of(value);
                case "appendonly" -> c.appendonly = parseYesNo(flag, value);
                case "appendfsync" -> c.appendfsync = switch (value.toLowerCase(Locale.ROOT)) {
                    case "always" -> Fsync.ALWAYS;
                    case "everysec" -> Fsync.EVERYSEC;
                    case "no" -> Fsync.NO;
                    default -> throw new IllegalArgumentException(
                            "--appendfsync must be always, everysec or no, got '" + value + "'");
                };
                case "hz" -> c.hz = parseInt(flag, value, 1, 500);
                case "loglevel" -> c.loglevel = switch (value.toLowerCase(Locale.ROOT)) {
                    case "debug", "verbose", "notice", "warning" -> value.toLowerCase(Locale.ROOT);
                    default -> throw new IllegalArgumentException(
                            "--loglevel must be debug, verbose, notice or warning, got '" + value + "'");
                };
                case "client-output-buffer-limit-pubsub" -> c.parsePubsubLimit(value);
                case "aof-fsync-thread" -> c.aofFsyncThread = parseYesNo(flag, value);
                default -> throw new IllegalArgumentException("unknown flag " + flag);
            }
        }
        return c;
    }

    /** The values {@code CONFIG GET} can see, by redis.conf name. */
    public Map<String, String> asMap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("port", Integer.toString(port));
        m.put("bind", bind);
        m.put("dir", dir.toAbsolutePath().normalize().toString());
        m.put("appendonly", appendonly ? "yes" : "no");
        m.put("appendfsync", appendfsync.name().toLowerCase(Locale.ROOT));
        m.put("appendfilename", "appendonly.aof");
        m.put("hz", Integer.toString(hz));
        m.put("loglevel", loglevel);
        // No RDB snapshots: an empty save line, which is what redis-benchmark asks for at startup.
        m.put("save", "");
        m.put("databases", "1");
        m.put("client-output-buffer-limit",
                "normal 0 0 0 slave 0 0 0 pubsub " + pubsubHardLimit + " " + pubsubSoftLimit + " " + pubsubSoftSeconds);
        return m;
    }

    @Override
    public String toString() {
        return "port=" + port + " bind=" + bind + " dir=" + dir.toAbsolutePath().normalize()
                + " appendonly=" + (appendonly ? "yes" : "no")
                + " appendfsync=" + appendfsync.name().toLowerCase(Locale.ROOT) + " hz=" + hz;
    }

    private void parsePubsubLimit(String value) {
        String[] parts = value.trim().split("\\s+");
        if (parts.length != 3) {
            throw new IllegalArgumentException("--client-output-buffer-limit-pubsub wants '<hard> <soft> <seconds>'");
        }
        pubsubHardLimit = parseMemory(parts[0]);
        pubsubSoftLimit = parseMemory(parts[1]);
        pubsubSoftSeconds = parseInt("soft seconds", parts[2], 0, Integer.MAX_VALUE);
    }

    static long parseMemory(String s) {
        String v = s.toLowerCase(Locale.ROOT);
        long mul = 1;
        if (v.endsWith("gb")) { mul = 1L << 30; v = v.substring(0, v.length() - 2); }
        else if (v.endsWith("mb")) { mul = 1L << 20; v = v.substring(0, v.length() - 2); }
        else if (v.endsWith("kb")) { mul = 1L << 10; v = v.substring(0, v.length() - 2); }
        else if (v.endsWith("b")) { v = v.substring(0, v.length() - 1); }
        try {
            return Long.parseLong(v) * mul;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad memory size '" + s + "'");
        }
    }

    private static int parseInt(String flag, String value, int min, int max) {
        try {
            int v = Integer.parseInt(value);
            if (v < min || v > max) {
                throw new IllegalArgumentException(flag + " must be between " + min + " and " + max + ", got " + v);
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(flag + " must be a number, got '" + value + "'");
        }
    }

    private static boolean parseYesNo(String flag, String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "yes" -> true;
            case "no" -> false;
            default -> throw new IllegalArgumentException(flag + " must be yes or no, got '" + value + "'");
        };
    }
}
