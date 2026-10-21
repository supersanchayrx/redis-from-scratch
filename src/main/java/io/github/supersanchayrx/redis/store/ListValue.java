package io.github.supersanchayrx.redis.store;

import io.github.supersanchayrx.redis.ds.LinkedList;

/** A list value, backed by the hand-written doubly linked list. */
public final class ListValue implements Value {

    public final LinkedList<byte[]> list = new LinkedList<>();

    @Override
    public String typeName() {
        return "list";
    }
}
