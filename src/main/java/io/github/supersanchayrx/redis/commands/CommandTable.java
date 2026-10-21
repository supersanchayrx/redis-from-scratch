package io.github.supersanchayrx.redis.commands;

import io.github.supersanchayrx.redis.server.Client;
import io.github.supersanchayrx.redis.store.WrongTypeException;
import io.github.supersanchayrx.redis.util.Bytes;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The single source of truth for commands: dispatch, arity errors, the subscribed-mode check and AOF
 * logging all read the same table.
 */
public final class CommandTable {

    private static final Logger LOG = Logger.getLogger("commands");

    private final Map<String, CommandSpec> byName = new HashMap<>();

    public static CommandTable standard() {
        CommandTable t = new CommandTable();
        ServerCommands.register(t);
        KeyCommands.register(t);
        ExpireCommands.register(t);
        StringCommands.register(t);
        ListCommands.register(t);
        HashCommands.register(t);
        ZSetCommands.register(t);
        PubSubCommands.register(t);
        return t;
    }

    public void add(String name, int arity, int flags, CommandSpec.Handler handler) {
        CommandSpec spec = new CommandSpec(name, arity, flags, handler);
        if (byName.put(spec.name(), spec) != null) {
            throw new IllegalStateException("duplicate command " + name);
        }
    }

    public CommandSpec lookup(byte[] name) {
        return byName.get(Bytes.lower(name));
    }

    public Collection<CommandSpec> all() {
        return byName.values();
    }

    public int size() {
        return byName.size();
    }

    /**
     * Runs one command: lookup, arity, subscribed-mode check, the handler, then the AOF. Errors become
     * error replies; nothing a handler throws escapes to the event loop.
     */
    public void execute(Context ctx, Client c, byte[][] argv) {
        ctx.stats.commands++;
        CommandSpec cmd = lookup(argv[0]);
        if (cmd == null) {
            c.out.error(unknownCommand(argv));
            return;
        }
        if (!cmd.arityOk(argv.length)) {
            c.out.error("ERR wrong number of arguments for '" + cmd.name() + "' command");
            return;
        }
        if (c.isSubscribed() && !cmd.is(CommandSpec.PUBSUB)) {
            c.out.error("ERR Can't execute '" + cmd.name()
                    + "': only (P|S)SUBSCRIBE / (P|S)UNSUBSCRIBE / PING / QUIT / RESET are allowed in this context");
            return;
        }

        ctx.ks.setTime(ctx.clock.millis()); // one clock read per command
        long dirtyBefore = ctx.ks.dirty();
        ctx.propagated = false;
        try {
            cmd.handler().run(ctx, c, argv);
        } catch (CommandException e) {
            c.out.error(e.getMessage());
        } catch (WrongTypeException e) {
            c.out.error(WrongTypeException.MESSAGE);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "command " + cmd.name() + " failed", e);
            c.out.error("ERR internal error in '" + cmd.name() + "': " + e);
        }
        // Log the command as received unless the handler logged its own effect form (PXAT, PEXPIREAT, DEL).
        if (cmd.is(CommandSpec.WRITE) && !ctx.propagated && ctx.ks.dirty() != dirtyBefore) {
            ctx.aof.append(argv);
        }
    }

    /** Redis's text: {@code unknown command 'x', with args beginning with: 'a' 'b' }, capped near 128 bytes. */
    static String unknownCommand(byte[][] argv) {
        StringBuilder args = new StringBuilder();
        for (int i = 1; i < argv.length && args.length() < 128; i++) {
            String a = Bytes.str(argv[i]);
            int room = 128 - args.length();
            args.append('\'').append(a.length() > room ? a.substring(0, room) : a).append("' ");
        }
        String name = "ERR unknown command '" + CommandException.truncate(Bytes.str(argv[0]), 128) + "'";
        return argv.length == 1 ? name : name + ", with args beginning with: " + args;
    }
}
