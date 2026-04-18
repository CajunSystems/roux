package com.cajunsystems.roux;

import com.cajunsystems.roux.runtime.DefaultEffectRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleTest {

    private DefaultEffectRuntime runtime;

    @BeforeEach
    void setUp() {
        runtime = DefaultEffectRuntime.create();
    }

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    // -----------------------------------------------------------------------
    // recurs
    // -----------------------------------------------------------------------

    @Test
    void recursStopper() throws Throwable {
        AtomicInteger count = new AtomicInteger(0);
        Effect<Throwable, Integer> effect = Effect.suspend(count::incrementAndGet);

        Integer result = runtime.unsafeRun(
                Schedule.<Integer>immediate().recurs(4).repeat(effect)
        );

        // initial run + 4 repetitions = 5 total executions; result = last output
        assertEquals(5, count.get());
        assertEquals(5, result);
    }

    @Test
    void recursZeroRunsExactlyOnce() throws Throwable {
        AtomicInteger count = new AtomicInteger(0);

        runtime.unsafeRun(
                Schedule.<Integer>immediate().recurs(0).repeat(Effect.suspend(count::incrementAndGet))
        );

        assertEquals(1, count.get());
    }

    // -----------------------------------------------------------------------
    // whileOutput / untilOutput
    // -----------------------------------------------------------------------

    @Test
    void whileOutputStopsWhenPredicateFails() throws Throwable {
        AtomicInteger count = new AtomicInteger(0);

        Integer result = runtime.unsafeRun(
                Schedule.<Integer>immediate()
                        .whileOutput(n -> n < 5)
                        .repeat(Effect.suspend(count::incrementAndGet))
        );

        // Stops when output reaches 5 (predicate fails)
        assertEquals(5, count.get());
        assertEquals(5, result);
    }

    @Test
    void untilOutputStopsWhenPredicateSucceeds() throws Throwable {
        AtomicInteger count = new AtomicInteger(0);

        Integer result = runtime.unsafeRun(
                Schedule.<Integer>immediate()
                        .untilOutput(n -> n >= 3)
                        .repeat(Effect.suspend(count::incrementAndGet))
        );

        assertEquals(3, count.get());
        assertEquals(3, result);
    }

    // -----------------------------------------------------------------------
    // collect
    // -----------------------------------------------------------------------

    @Test
    void collectAccumulatesAllOutputs() throws Throwable {
        AtomicInteger count = new AtomicInteger(0);

        List<Integer> results = runtime.unsafeRun(
                Schedule.<Integer>immediate()
                        .recurs(2)
                        .collect()
                        .repeat(Effect.suspend(count::incrementAndGet))
        );

        // initial run + 2 repetitions = 3 outputs
        assertEquals(List.of(1, 2, 3), results);
    }

    @Test
    void collectWithWhileOutput() throws Throwable {
        AtomicInteger count = new AtomicInteger(0);

        List<Integer> results = runtime.unsafeRun(
                Schedule.<Integer>immediate()
                        .whileOutput(n -> n < 4)
                        .collect()
                        .repeat(Effect.suspend(count::incrementAndGet))
        );

        // Runs 1, 2, 3 (continue), then 4 (stop) — 4 is included in the collection
        assertEquals(List.of(1, 2, 3, 4), results);
    }

    @Test
    void collectEmptyWhenRecursZero() throws Throwable {
        AtomicInteger count = new AtomicInteger(0);

        List<Integer> results = runtime.unsafeRun(
                Schedule.<Integer>immediate()
                        .recurs(0)
                        .collect()
                        .repeat(Effect.suspend(count::incrementAndGet))
        );

        // recurs(0) stops immediately after the first run
        assertEquals(List.of(1), results);
        assertEquals(1, count.get());
    }

    // -----------------------------------------------------------------------
    // fixed delay
    // -----------------------------------------------------------------------

    @Test
    void fixedScheduleRunsAtLeastOnce() throws Throwable {
        AtomicInteger count = new AtomicInteger(0);

        runtime.unsafeRun(
                Schedule.<Integer>fixed(Duration.ZERO)
                        .recurs(2)
                        .repeat(Effect.suspend(count::incrementAndGet))
        );

        assertEquals(3, count.get());
    }

    // -----------------------------------------------------------------------
    // exponential
    // -----------------------------------------------------------------------

    @Test
    void exponentialScheduleWithMaxDelay() throws Throwable {
        AtomicInteger count = new AtomicInteger(0);
        // Use tiny base so the test doesn't actually sleep long
        runtime.unsafeRun(
                Schedule.<Integer>exponential(Duration.ofMillis(1))
                        .maxDelay(Duration.ofMillis(5))
                        .recurs(3)
                        .repeat(Effect.suspend(count::incrementAndGet))
        );

        assertEquals(4, count.get());
    }

    // -----------------------------------------------------------------------
    // maxDelay
    // -----------------------------------------------------------------------

    @Test
    void maxDelayCapsExponentialGrowth() throws Throwable {
        AtomicInteger count = new AtomicInteger(0);

        runtime.unsafeRun(
                Schedule.<Integer>exponential(Duration.ofMillis(1))
                        .maxDelay(Duration.ofMillis(2))
                        .recurs(5)
                        .repeat(Effect.suspend(count::incrementAndGet))
        );

        assertEquals(6, count.get());
    }

    // -----------------------------------------------------------------------
    // jittered
    // -----------------------------------------------------------------------

    @Test
    void jitteredDoesNotChangeRepeatCount() throws Throwable {
        AtomicInteger count = new AtomicInteger(0);

        runtime.unsafeRun(
                Schedule.<Integer>fixed(Duration.ZERO)
                        .jittered(0.5)
                        .recurs(3)
                        .repeat(Effect.suspend(count::incrementAndGet))
        );

        assertEquals(4, count.get());
    }

    // -----------------------------------------------------------------------
    // Composition with RetryPolicy
    // -----------------------------------------------------------------------

    @Test
    void composeWithRetryPolicy() throws Throwable {
        AtomicInteger attempts = new AtomicInteger(0);
        AtomicInteger scheduleRuns = new AtomicInteger(0);

        // Effect fails twice, then succeeds on 3rd attempt
        Effect<Throwable, Integer> flaky = Effect.suspend(() -> {
            int n = attempts.incrementAndGet();
            if (n % 3 != 0) throw new RuntimeException("transient");
            scheduleRuns.incrementAndGet();
            return n;
        });

        List<Integer> results = runtime.unsafeRun(
                Schedule.<Integer>immediate()
                        .recurs(1)
                        .collect()
                        .repeat(flaky.retry(RetryPolicy.immediate().maxAttempts(5)))
        );

        // Two successful schedule runs: attempts 3 and 6
        assertEquals(2, scheduleRuns.get());
        assertEquals(2, results.size());
    }

    // -----------------------------------------------------------------------
    // Effect.effect() — no-handler entry point
    // -----------------------------------------------------------------------

    @Test
    void effectBuilderWithoutHandler() throws Throwable {
        Effect<Throwable, String> program = Effect.effect(ctx -> {
            String a = ctx.yield(Effect.succeed("hello"));
            String b = ctx.yield(Effect.succeed(" world"));
            return a + b;
        });

        String result = runtime.unsafeRun(program);
        assertEquals("hello world", result);
    }

    @Test
    void effectBuilderPropagatesTypedError() {
        Effect<Throwable, String> program = Effect.effect(ctx -> {
            ctx.yield(Effect.fail(new RuntimeException("from yield")));
            return "never";
        });

        assertThrows(RuntimeException.class, () -> runtime.unsafeRun(program));
    }
}
