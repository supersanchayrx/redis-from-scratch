package io.github.supersanchayrx.redis.server;

import java.util.ArrayList;
import java.util.List;

/**
 * Periodic tasks run by the event loop itself. No java.util.Timer or ScheduledExecutorService: those run
 * on their own threads, and only the loop thread may touch state.
 */
public final class Timers {

    private static final class Timer {
        final long periodNanos;
        final Runnable task;
        long due;

        Timer(long periodNanos, Runnable task, long due) {
            this.periodNanos = periodNanos;
            this.task = task;
            this.due = due;
        }
    }

    private final List<Timer> timers = new ArrayList<>();

    /** Runs {@code task} every {@code periodMs}, first one period from {@code nowNanos}. */
    public void every(long periodMs, long nowNanos, Runnable task) {
        long period = periodMs * 1_000_000L;
        timers.add(new Timer(period, task, nowNanos + period));
    }

    /** Nanoseconds until the earliest timer is due (0 if one is overdue), or Long.MAX_VALUE if none. */
    public long nanosUntilNext(long nowNanos) {
        long best = Long.MAX_VALUE;
        for (Timer t : timers) {
            best = Math.min(best, Math.max(0, t.due - nowNanos));
        }
        return best;
    }

    /** Runs every due timer once and schedules its next run. A late timer doesn't run twice to catch up. */
    public void runDue(long nowNanos) {
        for (Timer t : timers) {
            if (t.due <= nowNanos) {
                t.due += t.periodNanos;
                if (t.due <= nowNanos) {
                    t.due = nowNanos + t.periodNanos;
                }
                t.task.run();
            }
        }
    }
}
