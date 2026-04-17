package com.cajunsystems.roux.testing;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A virtual clock for use in tests. Time advances only when explicitly
 * told to via {@link #advance(Duration)} — it never ticks on its own.
 *
 * <p>Use with {@link TestRuntime} to control sleep behaviour in tests:
 * <pre>{@code
 * TestClock clock = new TestClock();
 * TestRuntime runtime = TestRuntime.createWithClock(clock);
 *
 * // Effect.sleep(1_second) advances virtual clock by 1s instead of blocking
 * runtime.unsafeRun(effect);
 * assertEquals(Duration.ofSeconds(1), clock.currentTime());
 * }</pre>
 */
public final class TestClock {

    private final AtomicLong virtualNanos = new AtomicLong(0);

    /** Advance virtual time by the given duration. */
    public void advance(Duration duration) {
        virtualNanos.addAndGet(duration.toNanos());
    }

    /** Current virtual time elapsed since creation. */
    public Duration currentTime() {
        return Duration.ofNanos(virtualNanos.get());
    }

    /** Reset virtual time to zero. */
    public void reset() {
        virtualNanos.set(0);
    }
}
