package io.github.supersanchayrx.redis;

/**
 * The time source, injected everywhere so tests can move time without sleeping.
 * Wall-clock milliseconds are for expiry times (absolute, so they survive restarts);
 * {@code nanos} is monotonic and is for timers and durations.
 */
public interface Clock {

    long millis();

    long nanos();

    Clock SYSTEM = new Clock() {
        @Override
        public long millis() {
            return System.currentTimeMillis();
        }

        @Override
        public long nanos() {
            return System.nanoTime();
        }
    };
}
