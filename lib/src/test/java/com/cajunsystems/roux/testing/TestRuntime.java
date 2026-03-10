package com.cajunsystems.roux.testing;

import com.cajunsystems.roux.runtime.DefaultEffectRuntime;

import java.time.Duration;
import java.util.concurrent.Executors;

/**
 * A test-specific runtime that overrides {@code Effect.sleep()} to advance
 * a virtual {@link TestClock} instead of blocking real time.
 *
 * <p>Usage:
 * <pre>{@code
 * TestRuntime runtime = TestRuntime.create();
 *
 * // Effect.sleep(1_second) runs instantly, virtual clock advances by 1s
 * runtime.unsafeRun(Effect.sleep(Duration.ofSeconds(1)));
 * assertEquals(Duration.ofSeconds(1), runtime.clock().currentTime());
 * }</pre>
 *
 * <p>Limitation: {@code Effect.timeout(Duration)} uses wall-clock time internally
 * and is NOT controlled by TestClock.
 */
public final class TestRuntime extends DefaultEffectRuntime {

    private final TestClock clock;

    private TestRuntime(TestClock clock) {
        super(Executors.newVirtualThreadPerTaskExecutor(), true);
        this.clock = clock;
    }

    /** Create a TestRuntime with a fresh TestClock. */
    public static TestRuntime create() {
        return new TestRuntime(new TestClock());
    }

    /** Create a TestRuntime using a specific TestClock (for shared clock scenarios). */
    public static TestRuntime createWithClock(TestClock clock) {
        return new TestRuntime(clock);
    }

    /** Return the virtual clock used by this runtime. */
    public TestClock clock() {
        return clock;
    }

    /**
     * Advance the virtual clock by {@code duration} instead of sleeping.
     * Called by the runtime when it encounters an {@code Effect.sleep()} effect.
     */
    @Override
    protected void performSleep(Duration duration) {
        clock.advance(duration);
    }
}
