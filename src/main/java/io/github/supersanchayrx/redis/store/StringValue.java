package io.github.supersanchayrx.redis.store;

/** A string value. INCR parses it as a 64-bit integer each time rather than storing integers specially. */
public record StringValue(byte[] bytes) implements Value {

    @Override
    public String typeName() {
        return "string";
    }
}
