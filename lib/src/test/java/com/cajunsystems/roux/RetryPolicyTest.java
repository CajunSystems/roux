package com.cajunsystems.roux;

import com.cajunsystems.roux.runtime.DefaultEffectRuntime;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RetryPolicyTest {

    private final EffectRuntime runtime = DefaultEffectRuntime.create();

    // -----------------------------------------------------------------------
    // RetryPolicy factories
    // -----------------------------------------------------------------------

    @Test
    void immediatePolicy_hasZeroDelay() {
        RetryPolicy policy = RetryPolicy.immediate().maxAttempts(3);
        assertEquals(Duration.ZERO, policy.computeDelay(0));
        assertEquals(Duration.ZERO, policy.computeDelay(2));
    }

    @Test
    void fixedPolicy_returnsConstantDelay() {
        RetryPolicy policy = RetryPolicy.fixed(Duration.ofMillis(200));
        assertEquals(Duration.ofMillis(200), policy.computeDelay(0));
        assertEquals(Duration.ofMillis(200), policy.computeDelay(5));
    }

    @Test
    void exponentialPolicy_doublesDelay() {
        RetryPolicy policy = RetryPolicy.exponential(Duration.ofMillis(100));
        assertEquals(Duration.ofMillis(100), policy.computeDelay(0));
        assertEquals(Duration.ofMillis(200), policy.computeDelay(1));
        assertEquals(Duration.ofMillis(400), policy.computeDelay(2));
        assertEquals(Duration.ofMillis(800), policy.computeDelay(3));
    }

    @Test
    void maxDelay_capsExponentialGrowth() {
        RetryPolicy policy = RetryPolicy.exponential(Duration.ofMillis(100))
                .maxDelay(Duration.ofMillis(350));
        assertEquals(Duration.ofMillis(100), policy.computeDelay(0));
        assertEquals(Duration.ofMillis(200), policy.computeDelay(1));
        assertEquals(Duration.ofMillis(350), policy.computeDelay(2)); // capped at 350
        assertEquals(Duration.ofMillis(350), policy.computeDelay(3)); // still capped
    }

    // -----------------------------------------------------------------------
    // maxAttempts
    // -----------------------------------------------------------------------

    @Test
    void maxAttempts_limitsRetries() {
        RetryPolicy policy = RetryPolicy.immediate().maxAttempts(2);
        assertTrue(policy.shouldRetry(new RuntimeException(), 0));
        assertTrue(policy.shouldRetry(new RuntimeException(), 1));
        assertFalse(policy.shouldRetry(new RuntimeException(), 2)); // exhausted
    }

    @Test
    void noMaxAttempts_retriesIndefinitely() {
        RetryPolicy policy = RetryPolicy.immediate(); // no maxAttempts set → unlimited
        assertTrue(policy.shouldRetry(new RuntimeException(), 0));
        assertTrue(policy.shouldRetry(new RuntimeException(), 9999));
    }

    @Test
    void maxAttemptsZero_neverRetries() {
        RetryPolicy policy = RetryPolicy.immediate().maxAttempts(0);
        assertFalse(policy.shouldRetry(new RuntimeException(), 0));
    }

    // -----------------------------------------------------------------------
    // retryWhen predicate
    // -----------------------------------------------------------------------

    @Test
    void retryWhen_onlyRetriesMatchingErrors() {
        RetryPolicy policy = RetryPolicy.immediate()
                .maxAttempts(5)
                .retryWhen(e -> e instanceof IOException);

        assertTrue(policy.shouldRetry(new IOException("transient"), 0));
        assertFalse(policy.shouldRetry(new IllegalArgumentException("permanent"), 0));
    }

    // -----------------------------------------------------------------------
    // Jitter
    // -----------------------------------------------------------------------

    @Test
    void jitter_producesDelayWithinExpectedRange() {
        RetryPolicy policy = RetryPolicy.fixed(Duration.ofMillis(1000))
                .withJitter(0.5); // ±50%

        // Run several samples to verify range
        for (int i = 0; i < 50; i++) {
            long millis = policy.computeDelay(0).toMillis();
            assertTrue(millis >= 500, "delay too low: " + millis);
            assertTrue(millis <= 1500, "delay too high: " + millis);
        }
    }

    @Test
    void jitterZero_producesExactDelay() {
        RetryPolicy policy = RetryPolicy.fixed(Duration.ofMillis(500)).withJitter(0.0);
        assertEquals(Duration.ofMillis(500), policy.computeDelay(0));
    }

    // -----------------------------------------------------------------------
    // Validation
    // -----------------------------------------------------------------------

    @Test
    void negativeMaxAttempts_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () ->
                RetryPolicy.immediate().maxAttempts(-1));
    }

    @Test
    void negativeDelay_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () ->
                RetryPolicy.fixed(Duration.ofMillis(-100)));
    }

    @Test
    void jitterOutOfRange_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () ->
                RetryPolicy.immediate().withJitter(1.5));
        assertThrows(IllegalArgumentException.class, () ->
                RetryPolicy.immediate().withJitter(-0.1));
    }

    @Test
    void nullPredicate_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () ->
                RetryPolicy.immediate().retryWhen(null));
    }

    // -----------------------------------------------------------------------
    // Effect.retry(RetryPolicy) integration
    // -----------------------------------------------------------------------

    @Test
    void retryPolicy_retriesOnFailure() throws Throwable {
        AtomicInteger attempts = new AtomicInteger(0);
        RetryPolicy policy = RetryPolicy.immediate().maxAttempts(3);

        Effect<Throwable, String> effect = Effect.<Throwable, String>suspend(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new RuntimeException("not yet");
            }
            return "success";
        }).retry(policy);

        String result = runtime.unsafeRun(effect);
        assertEquals("success", result);
        assertEquals(3, attempts.get());
    }

    @Test
    void retryPolicy_stopsAfterMaxAttempts() {
        AtomicInteger attempts = new AtomicInteger(0);
        RetryPolicy policy = RetryPolicy.immediate().maxAttempts(2);

        Effect<Throwable, String> effect = Effect.<Throwable, String>suspend(() -> {
            attempts.incrementAndGet();
            throw new RuntimeException("always fails");
        }).retry(policy);

        assertThrows(RuntimeException.class, () -> runtime.unsafeRun(effect));
        // 1 initial + 2 retries = 3 total
        assertEquals(3, attempts.get());
    }

    @Test
    void retryPolicy_doesNotRetryOnNonMatchingError() {
        AtomicInteger attempts = new AtomicInteger(0);
        RetryPolicy policy = RetryPolicy.immediate()
                .maxAttempts(5)
                .retryWhen(e -> e instanceof IOException);

        Effect<Throwable, String> effect = Effect.<Throwable, String>suspend(() -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("not retryable");
        }).retry(policy);

        assertThrows(IllegalStateException.class, () -> runtime.unsafeRun(effect));
        // Fails immediately with no retries
        assertEquals(1, attempts.get());
    }

    @Test
    void retryPolicy_succeedsOnFirstAttempt_noRetryMade() throws Throwable {
        AtomicInteger attempts = new AtomicInteger(0);
        RetryPolicy policy = RetryPolicy.immediate().maxAttempts(3);

        Effect<Throwable, String> effect = Effect.suspend(() -> {
            attempts.incrementAndGet();
            return "immediate success";
        }).retry(policy);

        String result = runtime.unsafeRun(effect);
        assertEquals("immediate success", result);
        assertEquals(1, attempts.get());
    }

    @Test
    void retryPolicy_fixedDelayBetweenAttempts() throws Throwable {
        AtomicInteger attempts = new AtomicInteger(0);
        long delayMs = 50;
        RetryPolicy policy = RetryPolicy.fixed(Duration.ofMillis(delayMs)).maxAttempts(2);

        Effect<Throwable, String> effect = Effect.<Throwable, String>suspend(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new RuntimeException("retry me");
            }
            return "done";
        }).retry(policy);

        long start = System.currentTimeMillis();
        String result = runtime.unsafeRun(effect);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals("done", result);
        // 2 retries × 50ms delay → at least 100ms elapsed
        assertTrue(elapsed >= 80, "Expected delay between retries, but elapsed only " + elapsed + "ms");
    }

    @Test
    void retryPolicy_exponentialBackoff_delaysGrow() throws Throwable {
        AtomicInteger attempts = new AtomicInteger(0);
        RetryPolicy policy = RetryPolicy.exponential(Duration.ofMillis(30))
                .maxAttempts(3);

        Effect<Throwable, String> effect = Effect.<Throwable, String>suspend(() -> {
            if (attempts.incrementAndGet() < 4) {
                throw new RuntimeException("growing delay");
            }
            return "done";
        }).retry(policy);

        long start = System.currentTimeMillis();
        String result = runtime.unsafeRun(effect);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals("done", result);
        // 30 + 60 + 120 = 210ms minimum
        assertTrue(elapsed >= 150, "Expected exponential delay, but elapsed only " + elapsed + "ms");
    }

    @Test
    void retryPolicy_maxDelay_capsExponentialInEffect() throws Throwable {
        AtomicInteger attempts = new AtomicInteger(0);
        RetryPolicy policy = RetryPolicy.exponential(Duration.ofMillis(100))
                .maxDelay(Duration.ofMillis(110))
                .maxAttempts(2);

        Effect<Throwable, String> effect = Effect.<Throwable, String>suspend(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new RuntimeException("retry");
            }
            return "done";
        }).retry(policy);

        long start = System.currentTimeMillis();
        runtime.unsafeRun(effect);
        long elapsed = System.currentTimeMillis() - start;

        // With cap, 2 retries at most 110ms each → at most ~220ms total
        // Without cap they would be 100ms + 200ms = 300ms
        assertTrue(elapsed < 250,
                "Expected delays to be capped, but elapsed " + elapsed + "ms");
    }

    @Test
    void retryPolicy_composesWithOtherCombinators() throws Throwable {
        RetryPolicy policy = RetryPolicy.immediate().maxAttempts(3);

        Effect<Throwable, String> effect = Effect.<Throwable, String>fail(new RuntimeException("boom"))
                .retry(policy)
                .catchAll(e -> Effect.succeed("recovered: " + e.getMessage()));

        String result = runtime.unsafeRun(effect);
        assertEquals("recovered: boom", result);
    }
}
