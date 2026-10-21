package io.github.supersanchayrx.redis.commands;

/**
 * A command failed with an error reply. The message is the full error line after the dash, with its
 * code, e.g. {@code "ERR syntax error"}. Every string here was copied from Redis, not written from memory.
 */
public final class CommandException extends RuntimeException {

    public static final CommandException SYNTAX = new CommandException("ERR syntax error");
    public static final CommandException NOT_INTEGER =
            new CommandException("ERR value is not an integer or out of range");
    public static final CommandException NOT_FLOAT = new CommandException("ERR value is not a valid float");
    public static final CommandException OVERFLOW =
            new CommandException("ERR increment or decrement would overflow");
    public static final CommandException NOT_POSITIVE =
            new CommandException("ERR value is out of range, must be positive");
    public static final CommandException NO_SUCH_KEY = new CommandException("ERR no such key");
    public static final CommandException INDEX_OUT_OF_RANGE = new CommandException("ERR index out of range");
    public static final CommandException MIN_MAX_NOT_FLOAT = new CommandException("ERR min or max is not a float");

    public CommandException(String message) {
        super(message, null, false, false);
    }

    public static CommandException invalidExpire(String cmd) {
        return new CommandException("ERR invalid expire time in '" + cmd + "' command");
    }

    public static CommandException wrongArgs(String cmd) {
        return new CommandException("ERR wrong number of arguments for '" + cmd + "' command");
    }

    public static CommandException unknownSubcommand(String sub, String cmdUpper) {
        return new CommandException("ERR unknown subcommand '" + truncate(sub, 128) + "'. Try " + cmdUpper + " HELP.");
    }

    static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
