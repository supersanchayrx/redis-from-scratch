package io.github.supersanchayrx.redis;

/** A clock that only moves when told to, so "an hour later" takes no time. */
public final class FakeClock implements Clock {

    private long millis;
    private long nanos;
    /** Added to nanos on every read, for tests that need time to pass while code runs. */
    public long nanosPerRead;

    public FakeClock(long startMillis) {
        this.millis = startMillis;
        this.nanos = 1_000_000_000L;
    }

    @Override
    public long millis() {
        return millis;
    }

    @Override
    public long nanos() {
        nanos += nanosPerRead;
        return nanos;
    }

    public void advanceMillis(long ms) {
        millis += ms;
        nanos += ms * 1_000_000L;
    }
}
