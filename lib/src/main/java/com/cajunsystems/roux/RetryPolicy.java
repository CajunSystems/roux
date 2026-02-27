package com.cajunsystems.roux;

import java.time.Duration;
import java.util.Random;
import java.util.function.Predicate;

/**
 * Describes how to retry a failed {@link Effect}.
 *
 * <p>Build a policy using the static factories and then chain the fluent
 * builder methods:
 *
 * <pre>{@code
 * RetryPolicy policy = RetryPolicy.exponential(Duration.ofMillis(100))
 *     .withJitter(0.2)
 *     .maxDelay(Duration.ofSeconds(30))
 *     .maxAttempts(5)
 *     .retryWhen(e -> e instanceof IOException);
 *
 * effect.retry(policy);
 * }</pre>
 *
 * <h2>Backoff strategies</h2>
 * <ul>
 *   <li>{@link #immediate()} – retry with no delay</li>
 *   <li>{@link #fixed(Duration)} – constant delay between attempts</li>
 *   <li>{@link #exponential(Duration)} – delay doubles on each attempt
 *       (base, base*2, base*4, …)</li>
 * </ul>
 */
public final class RetryPolicy {

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private final BackoffStrategy backoff;
    private final int maxAttempts;           // -1 = unlimited
    private final Duration maxDelay;         // null = no cap
    private final double jitterFactor;       // 0.0 = no jitter
    private final Predicate<Throwable> shouldRetry;

    // -----------------------------------------------------------------------
    // Private constructor
    // -----------------------------------------------------------------------

    private RetryPolicy(
            BackoffStrategy backoff,
            int maxAttempts,
            Duration maxDelay,
            double jitterFactor,
            Predicate<Throwable> shouldRetry
    ) {
        this.backoff = backoff;
        this.maxAttempts = maxAttempts;
        this.maxDelay = maxDelay;
        this.jitterFactor = jitterFactor;
        this.shouldRetry = shouldRetry;
    }

    // -----------------------------------------------------------------------
    // Static factories
    // -----------------------------------------------------------------------

    /** Retry immediately with no delay. */
    public static RetryPolicy immediate() {
        return new RetryPolicy(BackoffStrategy.NONE, -1, null, 0.0, __ -> true);
    }

    /**
     * Retry with a constant {@code delay} between each attempt.
     *
     * @param delay the fixed pause between retries
     */
    public static RetryPolicy fixed(Duration delay) {
        if (delay == null || delay.isNegative()) {
            throw new IllegalArgumentException("delay must be non-negative");
        }
        return new RetryPolicy(BackoffStrategy.fixed(delay), -1, null, 0.0, __ -> true);
    }

    /**
     * Retry with exponential back-off: the delay doubles on each attempt
     * ({@code base}, {@code base * 2}, {@code base * 4}, …).
     *
     * @param base the initial delay before the first retry
     */
    public static RetryPolicy exponential(Duration base) {
        if (base == null || base.isNegative()) {
            throw new IllegalArgumentException("base delay must be non-negative");
        }
        return new RetryPolicy(BackoffStrategy.exponential(base), -1, null, 0.0, __ -> true);
    }

    // -----------------------------------------------------------------------
    // Builder methods
    // -----------------------------------------------------------------------

    /**
     * Cap the maximum number of retry attempts.
     * The initial execution is not counted as an attempt.
     *
     * @param maxAttempts maximum number of retries (≥ 0)
     */
    public RetryPolicy maxAttempts(int maxAttempts) {
        if (maxAttempts < 0) {
            throw new IllegalArgumentException("maxAttempts must be >= 0");
        }
        return new RetryPolicy(backoff, maxAttempts, maxDelay, jitterFactor, shouldRetry);
    }

    /**
     * Cap the computed delay at {@code maxDelay}.
     * Useful with exponential back-off to prevent very long waits.
     *
     * @param maxDelay the ceiling on the computed delay
     */
    public RetryPolicy maxDelay(Duration maxDelay) {
        if (maxDelay == null || maxDelay.isNegative()) {
            throw new IllegalArgumentException("maxDelay must be non-negative");
        }
        return new RetryPolicy(backoff, maxAttempts, maxDelay, jitterFactor, shouldRetry);
    }

    /**
     * Add random jitter to computed delays.
     *
     * <p>The actual delay is chosen uniformly at random in the range
     * {@code [delay * (1 - factor), delay * (1 + factor)]}.
     *
     * @param factor jitter factor in [0.0, 1.0]
     */
    public RetryPolicy withJitter(double factor) {
        if (factor < 0.0 || factor > 1.0) {
            throw new IllegalArgumentException("jitter factor must be in [0.0, 1.0]");
        }
        return new RetryPolicy(backoff, maxAttempts, maxDelay, factor, shouldRetry);
    }

    /**
     * Only retry when the given predicate returns {@code true} for the error.
     * By default all errors are retried.
     *
     * @param predicate returns {@code true} if this error should be retried
     */
    public RetryPolicy retryWhen(Predicate<Throwable> predicate) {
        if (predicate == null) throw new IllegalArgumentException("predicate must not be null");
        return new RetryPolicy(backoff, maxAttempts, maxDelay, jitterFactor, predicate);
    }

    // -----------------------------------------------------------------------
    // Internal API (package-private — used by Effect.retry(RetryPolicy))
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} if another retry should be performed given this
     * error and the number of retries already made ({@code retriesSoFar}).
     */
    boolean shouldRetry(Throwable error, int retriesSoFar) {
        if (maxAttempts >= 0 && retriesSoFar >= maxAttempts) return false;
        return shouldRetry.test(error);
    }

    /**
     * Returns the delay to wait before the next attempt.
     * {@code retriesSoFar} is 0 before the first retry, 1 before the second, etc.
     */
    Duration computeDelay(int retriesSoFar) {
        Duration base = backoff.compute(retriesSoFar);

        // Apply max-delay cap
        if (maxDelay != null && base.compareTo(maxDelay) > 0) {
            base = maxDelay;
        }

        // Apply jitter
        if (jitterFactor > 0.0 && !base.isZero()) {
            long millis = base.toMillis();
            long spread = (long) (millis * jitterFactor);
            long jitter = spread == 0 ? 0 : (ThreadLocalRandom.current().nextLong(spread * 2 + 1) - spread);
            long result = millis + jitter;
            base = Duration.ofMillis(Math.max(0, result));
        }

        return base;
    }

    // -----------------------------------------------------------------------
    // Backoff strategy (internal)
    // -----------------------------------------------------------------------

    private interface BackoffStrategy {
        Duration compute(int retriesSoFar);

        BackoffStrategy NONE = __ -> Duration.ZERO;

        static BackoffStrategy fixed(Duration delay) {
            return __ -> delay;
        }

        static BackoffStrategy exponential(Duration base) {
            return retriesSoFar -> {
                // Cap the shift to avoid overflow: 2^62 ms is ~146 million years
                int shift = Math.min(retriesSoFar, 62);
                long multiplier = 1L << shift;
                long baseMillis = base.toMillis();
                // Guard against long overflow
                long delayMillis = (baseMillis > Long.MAX_VALUE / multiplier)
                        ? Long.MAX_VALUE
                        : baseMillis * multiplier;
                return Duration.ofMillis(delayMillis);
            };
        }
    }

    // -----------------------------------------------------------------------
    // Minimal thread-local random (avoids java.util.concurrent.ThreadLocalRandom
    // import conflicts and keeps this class self-contained)
    // -----------------------------------------------------------------------

    private static final class ThreadLocalRandom {
        private static final java.util.concurrent.ThreadLocalRandom INSTANCE =
                java.util.concurrent.ThreadLocalRandom.current();

        static java.util.concurrent.ThreadLocalRandom current() {
            return java.util.concurrent.ThreadLocalRandom.current();
        }
    }
}
