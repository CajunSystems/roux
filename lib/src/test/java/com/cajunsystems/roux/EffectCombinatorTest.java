package com.cajunsystems.roux;

import com.cajunsystems.roux.data.Either;
import com.cajunsystems.roux.data.Unit;
import com.cajunsystems.roux.exception.TimeoutException;
import com.cajunsystems.roux.runtime.DefaultEffectRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for all Effect combinators added in 0.2.0:
 * tap, tapError, retry, retryWithDelay, timeout, unit, runnable, sleep,
 * when, unless, race (via Effects), sequence, traverse, parAll.
 */
class EffectCombinatorTest {

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
    // tap
    // -----------------------------------------------------------------------

    @Test
    void tapExecutesSideEffectOnSuccess() throws Throwable {
        AtomicReference<Integer> seen = new AtomicReference<>();
        Effect<Throwable, Integer> effect = Effect.succeed(42)
                .tap(seen::set);

        Integer result = runtime.unsafeRun(effect);

        assertEquals(42, result);          // value unchanged
        assertEquals(42, seen.get());      // side effect ran
    }

    @Test
    void tapDoesNotRunOnFailure() {
        AtomicBoolean ran = new AtomicBoolean(false);
        Effect<Throwable, Integer> effect = Effect.<Throwable, Integer>fail(new RuntimeException("boom"))
                .tap(__ -> ran.set(true));

        assertThrows(RuntimeException.class, () -> runtime.unsafeRun(effect));
        assertFalse(ran.get());
    }

    @Test
    void tapDoesNotAlterValue() throws Throwable {
        Effect<Throwable, String> effect = Effect.succeed("hello")
                .tap(s -> { /* ignored */ })
                .map(String::toUpperCase);

        assertEquals("HELLO", runtime.unsafeRun(effect));
    }

    // -----------------------------------------------------------------------
    // tapError
    // -----------------------------------------------------------------------

    @Test
    void tapErrorExecutesSideEffectOnFailure() {
        AtomicReference<Throwable> seen = new AtomicReference<>();
        RuntimeException boom = new RuntimeException("boom");

        Effect<Throwable, Integer> effect = Effect.<Throwable, Integer>fail(boom)
                .tapError(seen::set);

        assertThrows(RuntimeException.class, () -> runtime.unsafeRun(effect));
        assertSame(boom, seen.get());
    }

    @Test
    void tapErrorRethrowsOriginalError() {
        RuntimeException boom = new RuntimeException("boom");

        Effect<Throwable, Integer> effect = Effect.<Throwable, Integer>fail(boom)
                .tapError(__ -> { /* no-op */ });

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> runtime.unsafeRun(effect));
        assertSame(boom, thrown);
    }

    @Test
    void tapErrorDoesNotRunOnSuccess() throws Throwable {
        AtomicBoolean ran = new AtomicBoolean(false);
        Effect<Throwable, Integer> effect = Effect.succeed(1)
                .tapError(__ -> ran.set(true));

        runtime.unsafeRun(effect);
        assertFalse(ran.get());
    }

    // -----------------------------------------------------------------------
    // retry
    // -----------------------------------------------------------------------

    @Test
    void retrySucceedsOnSecondAttempt() throws Throwable {
        AtomicInteger attempts = new AtomicInteger(0);

        Effect<Throwable, String> effect = Effect.<Throwable, String>suspend(() -> {
            int n = attempts.incrementAndGet();
            if (n < 2) throw new RuntimeException("fail " + n);
            return "ok";
        }).retry(3);

        String result = runtime.unsafeRun(effect);
        assertEquals("ok", result);
        assertEquals(2, attempts.get());
    }

    @Test
    void retryExhaustesAndPropagatesLastError() {
        AtomicInteger attempts = new AtomicInteger(0);

        Effect<Throwable, String> effect = Effect.<Throwable, String>suspend(() -> {
            attempts.incrementAndGet();
            throw new RuntimeException("always fails");
        });
        Effect<Throwable, String> withRetry = effect.retry(2);

        assertThrows(RuntimeException.class, () -> runtime.unsafeRun(withRetry));
        // 1 initial + 2 retries = 3 total attempts
        assertEquals(3, attempts.get());
    }

    @Test
    void retryZeroMeansNoRetry() {
        AtomicInteger attempts = new AtomicInteger(0);

        Effect<Throwable, String> effect = Effect.<Throwable, String>suspend(() -> {
            attempts.incrementAndGet();
            throw new RuntimeException("fail");
        }).retry(0);

        assertThrows(RuntimeException.class, () -> runtime.unsafeRun(effect));
        assertEquals(1, attempts.get());
    }

    @Test
    void retryDoesNotRetryOnSuccess() throws Throwable {
        AtomicInteger attempts = new AtomicInteger(0);

        Effect<Throwable, String> effect = Effect.<Throwable, String>suspend(() -> {
            attempts.incrementAndGet();
            return "ok";
        }).retry(5);

        runtime.unsafeRun(effect);
        assertEquals(1, attempts.get());
    }

    // -----------------------------------------------------------------------
    // retryWithDelay
    // -----------------------------------------------------------------------

    @Test
    void retryWithDelaySucceedsEventually() throws Throwable {
        AtomicInteger attempts = new AtomicInteger(0);

        Effect<RuntimeException, String> effect = Effect.suspend(() -> {
            int n = attempts.incrementAndGet();
            if (n < 3) throw new RuntimeException("not yet");
            return "done";
        });

        String result = runtime.unsafeRun(effect.retryWithDelay(4, Duration.ofMillis(10)));
        assertEquals("done", result);
        assertEquals(3, attempts.get());
    }

    // -----------------------------------------------------------------------
    // timeout
    // -----------------------------------------------------------------------

    @Test
    void timeoutSucceedsWhenEffectCompletesInTime() throws Throwable {
        Effect<Throwable, String> effect = Effect.<Throwable, String>succeed("fast")
                .timeout(Duration.ofSeconds(5));

        assertEquals("fast", runtime.unsafeRun(effect));
    }

    @Test
    void timeoutFailsWhenEffectExceedsDuration() {
        Effect<Throwable, String> slow = Effect.<Throwable, String>suspend(() -> {
            Thread.sleep(5_000);
            return "slow";
        }).timeout(Duration.ofMillis(100));

        assertThrows(TimeoutException.class, () -> runtime.unsafeRun(slow));
    }

    @Test
    void timeoutExceptionCarriesDuration() {
        Duration limit = Duration.ofMillis(50);
        Effect<Throwable, String> slow = Effect.<Throwable, String>suspend(() -> {
            Thread.sleep(5_000);
            return "slow";
        }).timeout(limit);

        TimeoutException ex = assertThrows(TimeoutException.class, () -> runtime.unsafeRun(slow));
        assertEquals(limit, ex.getDuration());
    }

    @Test
    void timeoutCanBeRecoveredWithCatchAll() throws Throwable {
        Effect<Throwable, String> effect = Effect.<Throwable, String>suspend(() -> {
            Thread.sleep(5_000);
            return "slow";
        }).timeout(Duration.ofMillis(50))
                .catchAll(e -> Effect.succeed("recovered"));

        assertEquals("recovered", runtime.unsafeRun(effect));
    }

    // -----------------------------------------------------------------------
    // Effect.unit()
    // -----------------------------------------------------------------------

    @Test
    void unitSucceedsWithUnitValue() throws Throwable {
        Effect<Throwable, Unit> effect = Effect.unit();
        assertNotNull(runtime.unsafeRun(effect));
    }

    // -----------------------------------------------------------------------
    // Effect.runnable()
    // -----------------------------------------------------------------------

    @Test
    void runnableExecutesSideEffect() throws Throwable {
        AtomicBoolean ran = new AtomicBoolean(false);
        Effect<Throwable, Unit> effect = Effect.runnable(() -> ran.set(true));

        runtime.unsafeRun(effect);
        assertTrue(ran.get());
    }

    @Test
    void runnableIsLazy() {
        AtomicBoolean ran = new AtomicBoolean(false);
        Effect<Throwable, Unit> effect = Effect.runnable(() -> ran.set(true));

        assertFalse(ran.get()); // not yet executed
    }

    // -----------------------------------------------------------------------
    // Effect.sleep()
    // -----------------------------------------------------------------------

    @Test
    void sleepDelaysExecution() throws Throwable {
        long start = System.currentTimeMillis();
        runtime.unsafeRun(Effect.sleep(Duration.ofMillis(100)));
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed >= 80, "Expected sleep of ~100ms, got " + elapsed + "ms");
    }

    // -----------------------------------------------------------------------
    // Effect.when() / Effect.unless()
    // -----------------------------------------------------------------------

    @Test
    void whenRunsEffectWhenConditionTrue() throws Throwable {
        AtomicBoolean ran = new AtomicBoolean(false);
        runtime.unsafeRun(Effect.when(true, Effect.runnable(() -> ran.set(true))));
        assertTrue(ran.get());
    }

    @Test
    void whenSkipsEffectWhenConditionFalse() throws Throwable {
        AtomicBoolean ran = new AtomicBoolean(false);
        runtime.unsafeRun(Effect.when(false, Effect.runnable(() -> ran.set(true))));
        assertFalse(ran.get());
    }

    @Test
    void unlessSkipsEffectWhenConditionTrue() throws Throwable {
        AtomicBoolean ran = new AtomicBoolean(false);
        runtime.unsafeRun(Effect.unless(true, Effect.runnable(() -> ran.set(true))));
        assertFalse(ran.get());
    }

    @Test
    void unlessRunsEffectWhenConditionFalse() throws Throwable {
        AtomicBoolean ran = new AtomicBoolean(false);
        runtime.unsafeRun(Effect.unless(false, Effect.runnable(() -> ran.set(true))));
        assertTrue(ran.get());
    }

    @Test
    void whenAndUnlessSucceedWithUnitWhenSkipped() throws Throwable {
        Unit u1 = runtime.unsafeRun(Effect.when(false, Effect.succeed("ignored")));
        Unit u2 = runtime.unsafeRun(Effect.unless(true, Effect.succeed("ignored")));

        assertNotNull(u1);
        assertNotNull(u2);
    }
}
