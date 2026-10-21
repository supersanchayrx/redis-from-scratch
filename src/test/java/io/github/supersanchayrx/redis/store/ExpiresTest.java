package io.github.supersanchayrx.redis.store;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The indexable expires set, against a HashMap model, with its indices checked throughout. */
class ExpiresTest {

    @Test
    void randomOpsMatchModel() {
        SplittableRandom rnd = new SplittableRandom(5);
        Expires e = new Expires();
        Map<Key, Long> model = new HashMap<>();
        for (int i = 0; i < 50_000; i++) {
            Key k = Key.of("k" + rnd.nextInt(2000));
            if (rnd.nextInt(3) == 0) {
                assertEquals(model.remove(k) != null, e.remove(k));
            } else {
                long at = rnd.nextLong(1_000_000);
                e.set(k, at);
                model.put(k, at);
            }
            if (i % 1000 == 0) {
                assertTrue(e.consistent());
                for (Map.Entry<Key, Long> m : model.entrySet()) {
                    assertEquals(m.getValue(), e.get(m.getKey()));
                }
            }
        }
        assertEquals(model.size(), e.size());
        assertTrue(e.consistent());
    }

    @Test
    void randomSamplingReachesEveryKey() {
        Expires e = new Expires();
        for (int i = 0; i < 100; i++) {
            e.set(Key.of("k" + i), i);
        }
        Set<Key> seen = new HashSet<>();
        SplittableRandom rnd = new SplittableRandom(6);
        for (int i = 0; i < 5000; i++) {
            seen.add(e.random(rnd));
        }
        assertEquals(100, seen.size());
        e.clear();
        assertNull(e.random(rnd));
    }
}
