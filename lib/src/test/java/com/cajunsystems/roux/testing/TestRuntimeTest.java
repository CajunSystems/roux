package com.cajunsystems.roux.testing;

import com.cajunsystems.roux.Effect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

import static com.cajunsystems.roux.testing.EffectAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class TestRuntimeTest {

    private TestRuntime runtime;

    @BeforeEach
    void setUp() {
        runtime = TestRuntime.create();
    }

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    // -----------------------------------------------------------------------
    // TestClock basics
    // -----------------------------------------------------------------------

    @Test
    void clock_startsAtZero() {
        assertEquals(Duration.ZERO, runtime.clock().currentTime());
    }

    @Test
    void clock_advancesWhenSleepEffectRuns() throws Throwable {
        runtime.unsafeRun(Effect.sleep(Duration.ofSeconds(5)));
        assertEquals(Duration.ofSeconds(5), runtime.clock().currentTime());
    }

    @Test
    void clock_accumulatesMultipleSleeps() throws Throwable {
        Effect<Throwable, Integer> effect = Effect.<Throwable>sleep(Duration.ofMillis(100))
                .flatMap(__ -> Effect.<Throwable>sleep(Duration.ofMillis(200)))
                .flatMap(__ -> Effect.succeed(42));

        runtime.unsafeRun(effect);

        assertEquals(Duration.ofMillis(300), runtime.clock().currentTime());
    }

    @Test
    void sleep_doesNotBlockRealTime() throws Throwable {
        long start = System.currentTimeMillis();
        runtime.unsafeRun(Effect.sleep(Duration.ofSeconds(60)));
        long elapsed = System.currentTimeMillis() - start;

        // Should complete in well under 1 second — no real sleeping
        assertTrue(elapsed < 500, "Expected sleep to be instant but took " + elapsed + "ms");
        assertEquals(Duration.ofSeconds(60), runtime.clock().currentTime());
    }

    // -----------------------------------------------------------------------
    // retryWithDelay uses virtual time
    // -----------------------------------------------------------------------

    @Test
    void retryWithDelay_usesVirtualTimeAndRunsFast() throws Throwable {
        // An effect that fails 3 times then succeeds
        int[] attempts = {0};
        Effect<IOException, String> effect = Effect.suspend(() -> {
            attempts[0]++;
            if (attempts[0] < 4) throw new IOException("not yet");
            return "done";
        });

        long start = System.currentTimeMillis();
        String result = runtime.unsafeRun(
                effect.retryWithDelay(3, Duration.ofSeconds(10)));
        long elapsed = System.currentTimeMillis() - start;

        assertEquals("done", result);
        assertEquals(4, attempts[0]);
        // 3 retries × 10s each = 30s virtual time, but 0 real time
        assertEquals(Duration.ofSeconds(30), runtime.clock().currentTime());
        assertTrue(elapsed < 500, "retryWithDelay should be instant with TestRuntime but took " + elapsed + "ms");
    }

    // -----------------------------------------------------------------------
    // Shared TestClock scenario
    // -----------------------------------------------------------------------

    @Test
    void createWithClock_sharesClockState() throws Throwable {
        TestClock sharedClock = new TestClock();
        try (TestRuntime r1 = TestRuntime.createWithClock(sharedClock)) {
            r1.unsafeRun(Effect.sleep(Duration.ofSeconds(3)));
        }
        assertEquals(Duration.ofSeconds(3), sharedClock.currentTime());
    }
}
