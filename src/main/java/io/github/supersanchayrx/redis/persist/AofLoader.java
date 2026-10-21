package io.github.supersanchayrx.redis.persist;

import io.github.supersanchayrx.redis.commands.CommandSpec;
import io.github.supersanchayrx.redis.commands.Context;
import io.github.supersanchayrx.redis.protocol.ProtocolException;
import io.github.supersanchayrx.redis.protocol.RespParser;
import io.github.supersanchayrx.redis.server.Client;
import io.github.supersanchayrx.redis.util.Bytes;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.logging.Logger;

/**
 * Replays the AOF at startup, before the port opens, through the same parser and dispatcher clients use.
 * A command cut off at the end of the file (a crash mid-write) is truncated away with a warning, like
 * Redis's {@code aof-load-truncated yes}; damage anywhere else stops the server, with the byte offset.
 */
public final class AofLoader {

    private static final Logger LOG = Logger.getLogger("aof");

    public record Result(long commands, long bytes, long truncatedTo, long bytesDropped, long errorReplies, long nanos) {

        public boolean truncated() {
            return truncatedTo >= 0;
        }
    }

    private AofLoader() {
    }

    public static Result load(Path file, Context ctx) throws IOException, AofCorruptException {
        long start = System.nanoTime();
        if (!Files.exists(file)) {
            return new Result(0, 0, -1, 0, 0, System.nanoTime() - start);
        }
        RespParser parser = new RespParser(false);
        Client fake = Client.fake();
        long commands = 0;
        long fed = 0;
        long lastGood = 0;
        long errorReplies = 0;
        ctx.ks.setLoading(true);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long size = ch.size();
            ByteBuffer chunk = ByteBuffer.allocate(256 * 1024);
            while (true) {
                chunk.clear();
                int n = ch.read(chunk);
                if (n < 0) {
                    break;
                }
                parser.feed(chunk.array(), 0, n);
                fed += n;
                while (true) {
                    byte[][] argv;
                    try {
                        argv = parser.next();
                    } catch (ProtocolException e) {
                        throw new AofCorruptException("Bad file format reading the append only file " + file
                                + " at offset " + lastGood + ": " + e.getMessage() + " near " + around(ch, lastGood));
                    }
                    if (argv == null) {
                        break;
                    }
                    CommandSpec spec = ctx.table.lookup(argv[0]);
                    if (spec == null || !spec.arityOk(argv.length)) {
                        throw new AofCorruptException((spec == null ? "Unknown command '" : "Wrong arity for '")
                                + Bytes.str(argv[0]) + "' in the append only file " + file + " at offset " + lastGood);
                    }
                    ctx.table.execute(ctx, fake, argv);
                    if (!fake.out.isEmpty()) {
                        if (fake.out.toByteArray()[0] == '-' && errorReplies++ < 5) {
                            long at = lastGood;
                            String reply = fake.out.toString().trim();
                            LOG.warning(() -> "Replaying the command at offset " + at + " gave an error: " + reply);
                        }
                        fake.out.clear();
                    }
                    commands++;
                    lastGood = fed - parser.buffered();
                }
            }
            if (parser.buffered() > 0 || !parser.atCommandBoundary()) {
                // The tail is the start of a command that never finished being written.
                long dropped = size - lastGood;
                LOG.warning("!!! Warning: short read while loading the AOF " + file + "! Truncating the AOF at offset "
                        + lastGood + " (" + dropped + " bytes of an incomplete command dropped)");
                ch.truncate(lastGood);
                ch.force(true);
                return new Result(commands, lastGood, lastGood, dropped, errorReplies, System.nanoTime() - start);
            }
            return new Result(commands, size, -1, 0, errorReplies, System.nanoTime() - start);
        } finally {
            ctx.ks.setLoading(false);
        }
    }

    /** Up to 32 bytes from {@code offset}, printable, for the error message. */
    private static String around(FileChannel ch, long offset) {
        try {
            ByteBuffer b = ByteBuffer.allocate(32);
            ch.read(b, offset);
            StringBuilder sb = new StringBuilder("\"");
            for (int i = 0; i < b.position(); i++) {
                int c = b.get(i) & 0xff;
                switch (c) {
                    case '\r' -> sb.append("\\r");
                    case '\n' -> sb.append("\\n");
                    default -> {
                        if (c >= 32 && c < 127) {
                            sb.append((char) c);
                        } else {
                            sb.append(String.format("\\x%02x", c));
                        }
                    }
                }
            }
            return sb.append('"').toString();
        } catch (IOException e) {
            return "(unreadable)";
        }
    }
}
