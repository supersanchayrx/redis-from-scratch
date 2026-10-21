package io.github.supersanchayrx.redis.store;

import io.github.supersanchayrx.redis.ds.SkipList;

import java.util.HashMap;

/**
 * A sorted set: the skip list for order and rank, plus a map from member to its skip-list node for O(1)
 * ZSCORE and membership. The node holds the score, so there's no boxed Double per member.
 */
public final class ZSetValue implements Value {

    public final SkipList list;
    public final HashMap<Key, SkipList.Node> dict = new HashMap<>();

    public ZSetValue() {
        this(new SkipList());
    }

    public ZSetValue(SkipList list) {
        this.list = list;
    }

    @Override
    public String typeName() {
        return "zset";
    }

    public int size() {
        return dict.size();
    }

    /** The member's score, or null if it isn't in the set. */
    public Double score(Key member) {
        SkipList.Node n = dict.get(member);
        return n == null ? null : n.score();
    }

    /** Adds the member or moves it to a new score. Returns true if it was new. */
    public boolean put(Key member, double score) {
        SkipList.Node n = dict.get(member);
        if (n == null) {
            dict.put(member, list.insert(score, member));
            return true;
        }
        if (n.score() != score) {
            dict.put(member, list.updateScore(n, score));
        }
        return false;
    }

    public boolean remove(Key member) {
        SkipList.Node n = dict.remove(member);
        if (n == null) {
            return false;
        }
        list.delete(n.score(), member);
        return true;
    }

    /** The 0-based rank of the member, or -1 if it isn't in the set. */
    public long rank(Key member) {
        SkipList.Node n = dict.get(member);
        return n == null ? -1 : list.rank(n.score(), member) - 1;
    }
}
