package com.cajunsystems.roux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * Composable repeat-on-success scheduling.
 *
 * <p>{@link RetryPolicy} handles the <em>failure</em> path (retry on error).
 * {@code Schedule} handles the <em>success</em> path — repeat an effect on a
 * cadence, while a predicate holds, or for a fixed number of iterations, and
 * optionally accumulate the results.
 *
 * <pre>{@code
 * // Poll every 2 s, up to 10 times, until done; collect all Status values
 * Schedule<Status, List<Status>> schedule = Schedule
 *     .<Status>fixed(Duration.ofSeconds(2))
 *     .recurs(10)
 *     .whileOutput(s -> !s.isDone())
 *     .collect();
 *
 * Effect<Throwable, List<Status>> polling = schedule.repeat(checkStatus);
 *
 * // Compose with RetryPolicy: retry transient failures, then repeat on success
 * schedule.repeat(riskyEffect.retry(RetryPolicy.exponential(Duration.ofMillis(50)).maxAttempts(3)));
 * }</pre>
 *
 * @param <A> the output type produced by each repetition of the effect
 * @param <B> the accumulated result type returned when the schedule stops
 */
public final class Schedule<A, B> {

    // -----------------------------------------------------------------------
    // Internal decision type
    // -----------------------------------------------------------------------

    private sealed interface Decision<B> {
        /** Continue after the given delay. */
        record Continue<B>(Duration delay) implements Decision<B> {}
        /** Stop and return the accumulated result. */
        record Stop<B>(B result) implements Decision<B> {}
    }

    /**
     * A single scheduling step: given the iteration count, the last output from
     * the effect, and the current accumulator, decide whether to continue or stop.
     *
     * <p>Note: built-in steps do not use {@code acc} for termination decisions —
     * only {@code iteration} and {@code output} matter. This invariant allows
     * {@link #collect()} to safely adapt steps across accumulator types.
     */
    @FunctionalInterface
    private interface Step<A, B> {
        Decision<B> decide(int iteration, A output, B acc);
    }

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private final Step<A, B> step;
    private final B initialAcc;
    private final BiFunction<B, A, B> accumulate;

    private Schedule(Step<A, B> step, B initialAcc, BiFunction<B, A, B> accumulate) {
        this.step = step;
        this.initialAcc = initialAcc;
        this.accumulate = accumulate;
    }

    // -----------------------------------------------------------------------
    // Static factories
    // -----------------------------------------------------------------------

    /** Repeat with a fixed {@code interval} between executions. Runs forever unless further constrained. */
    public static <A> Schedule<A, A> fixed(Duration interval) {
        if (interval == null || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be non-negative");
        }
        return new Schedule<>(
                (iteration, output, acc) -> new Decision.Continue<>(interval),
                null,
                (acc, output) -> output
        );
    }

    /** Repeat with exponentially increasing delay (base, base*2, base*4, …). Runs forever unless further constrained. */
    public static <A> Schedule<A, A> exponential(Duration base) {
        if (base == null || base.isNegative()) {
            throw new IllegalArgumentException("base must be non-negative");
        }
        return new Schedule<>(
                (iteration, output, acc) -> {
                    int shift = Math.min(iteration, 62);
                    long multiplier = 1L << shift;
                    long baseMillis = base.toMillis();
                    long delayMillis = (baseMillis > Long.MAX_VALUE / multiplier)
                            ? Long.MAX_VALUE
                            : baseMillis * multiplier;
                    return new Decision.Continue<>(Duration.ofMillis(delayMillis));
                },
                null,
                (acc, output) -> output
        );
    }

    /** Repeat immediately with no delay between executions. Runs forever unless further constrained. */
    public static <A> Schedule<A, A> immediate() {
        return new Schedule<>(
                (iteration, output, acc) -> new Decision.Continue<>(Duration.ZERO),
                null,
                (acc, output) -> output
        );
    }

    // -----------------------------------------------------------------------
    // Termination modifiers
    // -----------------------------------------------------------------------

    /** Stop after at most {@code n} repetitions (not counting the initial run). */
    public Schedule<A, B> recurs(int n) {
        if (n < 0) throw new IllegalArgumentException("n must be >= 0");
        Step<A, B> outer = this.step;
        return new Schedule<>(
                (iteration, output, acc) -> {
                    if (iteration >= n) return new Decision.Stop<>(acc);
                    return outer.decide(iteration, output, acc);
                },
                initialAcc,
                accumulate
        );
    }

    /**
     * Continue while {@code pred} returns {@code true} for the last output; stop as soon as it returns {@code false}.
     * When combined with {@link #collect()}, the output that caused the stop is still included in the collected list
     * because accumulation happens before the stop decision is evaluated.
     */
    public Schedule<A, B> whileOutput(Predicate<A> pred) {
        Step<A, B> outer = this.step;
        return new Schedule<>(
                (iteration, output, acc) -> {
                    if (!pred.test(output)) return new Decision.Stop<>(acc);
                    return outer.decide(iteration, output, acc);
                },
                initialAcc,
                accumulate
        );
    }

    /** Stop as soon as {@code pred} returns {@code true} for the last output. */
    public Schedule<A, B> untilOutput(Predicate<A> pred) {
        return whileOutput(pred.negate());
    }

    /** Cap the inter-repetition delay at {@code max}. */
    public Schedule<A, B> maxDelay(Duration max) {
        if (max == null || max.isNegative()) throw new IllegalArgumentException("max must be non-negative");
        Step<A, B> outer = this.step;
        return new Schedule<>(
                (iteration, output, acc) -> switch (outer.decide(iteration, output, acc)) {
                    case Decision.Continue<B> c ->
                        c.delay().compareTo(max) > 0 ? new Decision.Continue<>(max) : c;
                    case Decision.Stop<B> s -> s;
                },
                initialAcc,
                accumulate
        );
    }

    /** Add uniformly-distributed random jitter to computed delays. {@code factor} must be in [0.0, 1.0]. */
    public Schedule<A, B> jittered(double factor) {
        if (factor < 0.0 || factor > 1.0) throw new IllegalArgumentException("factor must be in [0.0, 1.0]");
        Step<A, B> outer = this.step;
        return new Schedule<>(
                (iteration, output, acc) -> switch (outer.decide(iteration, output, acc)) {
                    case Decision.Continue<B> c -> {
                        long millis = c.delay().toMillis();
                        if (millis == 0 || factor == 0.0) yield c;
                        long spread = (long) (millis * factor);
                        long jitter = spread == 0 ? 0
                                : (ThreadLocalRandom.current().nextLong(spread * 2 + 1) - spread);
                        yield new Decision.Continue<>(Duration.ofMillis(Math.max(0, millis + jitter)));
                    }
                    case Decision.Stop<B> s -> s;
                },
                initialAcc,
                accumulate
        );
    }

    // -----------------------------------------------------------------------
    // Accumulation
    // -----------------------------------------------------------------------

    /**
     * Collect all outputs into a {@link List}. The returned schedule's result
     * type changes from {@code B} to {@code List<A>}.
     *
     * <p>The outer step's {@code acc} parameter is passed {@code null} internally
     * because no built-in step uses it for termination — only iteration count and
     * output value drive continue/stop decisions.
     */
    @SuppressWarnings("unchecked")
    public Schedule<A, List<A>> collect() {
        Step<A, B> outer = this.step;
        Step<A, List<A>> wrappedStep = (iteration, output, acc) -> switch (outer.decide(iteration, output, null)) {
            case Decision.Continue<B> c -> new Decision.Continue<>(c.delay());
            case Decision.Stop<B> ignored -> new Decision.Stop<>(acc);
        };
        return new Schedule<>(
                wrappedStep,
                new ArrayList<>(),
                (list, a) -> {
                    List<A> next = new ArrayList<>(list);
                    next.add(a);
                    return next;
                }
        );
    }

    // -----------------------------------------------------------------------
    // Execution
    // -----------------------------------------------------------------------

    /**
     * Repeat {@code effect} according to this schedule, returning the final
     * accumulated result. The effect is always run at least once (the initial run).
     *
     * <p>The repeat loop is stack-safe because it builds a {@code flatMap} chain
     * that the runtime's trampolined interpreter evaluates without recursion.
     */
    public <E extends Throwable> Effect<Throwable, B> repeat(Effect<E, A> effect) {
        return repeatLoop(effect, 0, initialAcc);
    }

    private <E extends Throwable> Effect<Throwable, B> repeatLoop(
            Effect<E, A> effect, int iteration, B acc) {
        return effect.widen().flatMap(output -> {
            B nextAcc = accumulate != null ? accumulate.apply(acc, output) : acc;
            Decision<B> decision = step.decide(iteration, output, nextAcc);
            return switch (decision) {
                case Decision.Stop<B> stop -> Effect.succeed(stop.result());
                case Decision.Continue<B> cont -> cont.delay().isZero()
                        ? repeatLoop(effect, iteration + 1, nextAcc)
                        : Effect.<Throwable>sleep(cont.delay())
                            .flatMap(__ -> repeatLoop(effect, iteration + 1, nextAcc));
            };
        });
    }
}
