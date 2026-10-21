package io.github.supersanchayrx.redis;

import io.github.supersanchayrx.redis.persist.AofCorruptException;
import io.github.supersanchayrx.redis.persist.AofLoader;

import java.io.IOException;
import java.net.BindException;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Entry point: parse flags, replay the AOF, then open the port and run the loop on this thread.
 * Ctrl+C runs the shutdown hook, which stops the loop; the loop flushes and fsyncs the AOF on its way out.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws InterruptedException {
        Config config;
        try {
            config = Config.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("redis-from-scratch: " + e.getMessage());
            System.err.println("flags: --port N --bind ADDR --dir PATH --appendonly yes|no"
                    + " --appendfsync always|everysec|no --hz N --loglevel debug|verbose|notice|warning"
                    + " --aof-fsync-thread yes|no --client-output-buffer-limit-pubsub '32mb 8mb 60'");
            System.exit(1);
            return;
        }
        configureLogging(config.loglevel);
        Logger log = Logger.getLogger("server");
        log.info(() -> "Starting redis-from-scratch 0.1.0 (pid " + ProcessHandle.current().pid() + "): " + config);

        Server server = new Server(config);
        try {
            AofLoader.Result r = server.loadAof();
            if (r != null) {
                log.info(String.format("DB loaded from append only file: %d commands, %d bytes in %.3f seconds",
                        r.commands(), r.bytes(), r.nanos() / 1e9));
            }
        } catch (AofCorruptException e) {
            log.severe(e.getMessage());
            log.severe("Refusing to start. Fix or move the file aside; only a truncated tail is repaired automatically.");
            System.exit(1);
        } catch (IOException e) {
            log.severe("Can't read the append only file: " + e.getMessage());
            System.exit(1);
        }

        try {
            server.bind();
        } catch (BindException e) {
            log.severe("Can't listen on " + config.bind + ":" + config.port + ": " + e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            log.severe("Startup failed: " + e.getMessage());
            System.exit(1);
        }

        Thread loopThread = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (server.isRunning()) {
                log.warning("Received SIGINT/SIGTERM, scheduling shutdown...");
                server.stop();
                try {
                    loopThread.join(5000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "shutdown"));

        log.info(() -> "Ready to accept connections tcp on port " + server.port());
        server.run();
        log.info("redis-from-scratch is now ready to exit, bye bye...");
    }

    /** One line per record, Redis-style: "2026-10-22 03:19:00.123 INFO message". */
    static void configureLogging(String loglevel) {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tF %1$tT.%1$tL %4$s %5$s%6$s%n");
        LogManager.getLogManager().reset();
        Level level = switch (loglevel) {
            case "debug" -> Level.FINEST;
            case "verbose" -> Level.FINE;
            case "warning" -> Level.WARNING;
            default -> Level.INFO;
        };
        Logger root = Logger.getLogger("");
        Handler h = new ConsoleHandler();
        h.setFormatter(new SimpleFormatter());
        h.setLevel(level);
        root.addHandler(h);
        root.setLevel(level);
    }
}
