package com.cajunsystems.roux;

import com.cajunsystems.roux.capability.CapabilityHandler;
import com.cajunsystems.roux.capability.Capability;
import com.cajunsystems.roux.data.Either;
import com.cajunsystems.roux.data.ThrowingSupplier;
import com.cajunsystems.roux.data.Unit;

import java.time.Duration;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public sealed interface Effect<E extends Throwable, A> {

    // -----------------------------------------------------------------------
    // Primitive constructors (the effect algebra)
    // -----------------------------------------------------------------------

    record Pure<E extends Throwable, A>(A value) implements Effect<E, A> {}
    record Fail<E extends Throwable, A>(E error) implements Effect<E, A> {}
    record Suspend<E extends Throwable, A>(ThrowingSupplier<A> thunk) implements Effect<E, A> {}
    record FlatMap<E extends Throwable, A, B>(
            Effect<E, A> source,
            Function<A, Effect<E, B>> f
    ) implements Effect<E, B> {}
    record Fold<E extends Throwable, E2 extends Throwable, A, B>(
            Effect<E, A> source,
            Function<E, Effect<E2, B>> onError,
            Function<A, Effect<E2, B>> onSuccess
    ) implements Effect<E2, B> {}
    record MapError<E extends Throwable, E2 extends Throwable, A>(
            Effect<E, A> source,
            Function<E, E2> f
    ) implements Effect<E2, A> {}
    record Fork<E extends Throwable, A>(
            Effect<E, A> effect
    ) implements Effect<Throwable, Fiber<E, A>> {}
    record Scoped<E extends Throwable, A>(
            Function<EffectScope, Effect<E, A>> body
    ) implements Effect<E, A> {}
    record Generate<E extends Throwable, A>(
            EffectGenerator<E, A> generator,
            CapabilityHandler<Capability<?>> handler
    ) implements Effect<E, A> {}
    record PerformCapability<E extends Throwable, R>(
            Capability<R> capability
    ) implements Effect<E, R> {}
    /** Runs {@code effect} and fails with {@link com.cajunsystems.roux.exception.TimeoutException} if it
     *  does not complete within {@code duration}. Error type widens to {@link Throwable}. */
    record Timeout<E extends Throwable, A>(
            Effect<E, A> effect,
            Duration duration
    ) implements Effect<Throwable, A> {}
    /** Races a list of effects and returns the result of the first one to complete. */
    record Race<E extends Throwable, A>(
            java.util.List<Effect<E, A>> effects
    ) implements Effect<Throwable, A> {}
    /** Suspends execution for the given duration. Interpreted by the runtime. */
    record Sleep<E extends Throwable>(Duration duration) implements Effect<E, Unit> {}

    // -----------------------------------------------------------------------
    // Smart constructors
    // -----------------------------------------------------------------------

    static <E extends Throwable, A> Effect<E, A> succeed(A value) {
        return new Pure<>(value);
    }

    static <E extends Throwable, A> Effect<E, A> fail(E error) {
        return new Fail<>(error);
    }

    static <E extends Throwable, A> Effect<E, A> suspend(ThrowingSupplier<A> thunk) {
        return new Suspend<>(thunk);
    }

    /** Create an effect that succeeds with {@link Unit}. */
    static <E extends Throwable> Effect<E, Unit> unit() {
        return succeed(Unit.unit());
    }

    /**
     * Create an effect that runs a {@link Runnable} as a side effect and succeeds with
     * {@link Unit}. Exceptions thrown by the runnable propagate as errors.
     */
    static <E extends Throwable> Effect<E, Unit> runnable(Runnable action) {
        return suspend(() -> {
            action.run();
            return Unit.unit();
        });
    }

    /**
     * Sleep for the given duration. Interruption is respected — the sleep will
     * abort if the running thread is interrupted.
     */
    static <E extends Throwable> Effect<E, Unit> sleep(Duration duration) {
        return new Sleep<>(duration);
    }

    /**
     * Run {@code effect} only when {@code condition} is {@code true}.
     * When {@code false}, succeeds immediately with {@link Unit}.
     */
    @SuppressWarnings("unchecked")
    static <E extends Throwable> Effect<E, Unit> when(boolean condition, Effect<E, ?> effect) {
        if (condition) {
            return ((Effect<E, Object>) effect).map(__ -> Unit.unit());
        }
        return unit();
    }

    /**
     * Run {@code effect} only when {@code condition} is {@code false}.
     * When {@code true}, succeeds immediately with {@link Unit}.
     */
    static <E extends Throwable> Effect<E, Unit> unless(boolean condition, Effect<E, ?> effect) {
        return when(!condition, effect);
    }

    static <E extends Throwable, A> Effect<E, A> scoped(
            Function<EffectScope, Effect<E, A>> body
    ) {
        return new Scoped<>(body);
    }

    static <E extends Throwable, A> Effect<E, A> generate(
            EffectGenerator<E, A> generator,
            CapabilityHandler<Capability<?>> handler
    ) {
        return new Generate<>(generator, handler);
    }

    static <E extends Throwable, R> Effect<E, R> from(Capability<R> capability) {
        return new PerformCapability<>(capability);
    }

    // -----------------------------------------------------------------------
    // Transformation combinators
    // -----------------------------------------------------------------------

    default <B> Effect<E, B> map(Function<A, B> f) {
        return flatMap(a -> succeed(f.apply(a)));
    }

    default <B> Effect<E, B> flatMap(Function<A, Effect<E, B>> f) {
        return new FlatMap<>(this, f);
    }

    /**
     * Execute {@code action} as a side-effect on success, then pass the original
     * value through unchanged. Useful for logging, metrics, etc.
     */
    default Effect<E, A> tap(Consumer<A> action) {
        return flatMap(a -> {
            action.accept(a);
            return succeed(a);
        });
    }

    // -----------------------------------------------------------------------
    // Error-handling combinators
    // -----------------------------------------------------------------------

    default Effect<E, A> catchAll(Function<E, Effect<E, A>> handler) {
        return new Fold<>(this, handler, Effect::succeed);
    }

    /**
     * Execute {@code action} as a side-effect when this effect fails, then
     * re-throw the original error unchanged.
     */
    default Effect<E, A> tapError(Consumer<E> action) {
        return new Fold<>(
                this,
                e -> { action.accept(e); return fail(e); },
                Effect::succeed
        );
    }

    default <E2 extends Throwable> Effect<E2, A> mapError(Function<E, E2> f) {
        return new MapError<>(this, f);
    }

    @SuppressWarnings("unchecked")
    default Effect<Throwable, A> widen() {
        return (Effect<Throwable, A>) this;
    }

    @SuppressWarnings("unchecked")
    default <E2 extends E> Effect<E2, A> narrow() {
        return (Effect<E2, A>) this;
    }

    default Effect<E, A> orElse(Effect<E, A> fallback) {
        return catchAll(e -> fallback);
    }

    default Effect<Throwable, Either<E, A>> attempt() {
        return new Fold<>(
                this,
                e -> succeed(Either.left(e)),
                a -> succeed(Either.right(a))
        );
    }

    /**
     * Retry this effect up to {@code maxAttempts} additional times on failure.
     * Retries happen immediately without delay; use {@link #retryWithDelay} for
     * back-off strategies, or {@link #retry(RetryPolicy)} for full control.
     *
     * @param maxAttempts the maximum number of additional attempts (0 = no retry)
     */
    default Effect<E, A> retry(int maxAttempts) {
        if (maxAttempts <= 0) return this;
        Effect<E, A> self = this;
        return self.catchAll(__ -> self.retry(maxAttempts - 1));
    }

    /**
     * Retry this effect up to {@code maxAttempts} additional times, sleeping
     * {@code delay} between each attempt.
     *
     * @param maxAttempts the maximum number of additional attempts
     * @param delay       pause between each retry
     */
    default Effect<Throwable, A> retryWithDelay(int maxAttempts, Duration delay) {
        if (maxAttempts <= 0) return this.widen();
        Effect<E, A> self = this;
        return self.widen().catchAll(__ ->
                Effect.<Throwable>sleep(delay).flatMap(_u -> self.retryWithDelay(maxAttempts - 1, delay))
        );
    }

    /**
     * Retry this effect according to the given {@link RetryPolicy}.
     *
     * <p>The policy controls:
     * <ul>
     *   <li>maximum number of retries</li>
     *   <li>delay between retries (immediate, fixed, or exponential)</li>
     *   <li>an optional jitter spread to avoid thundering-herd problems</li>
     *   <li>a per-error predicate to skip retrying on unrecoverable errors</li>
     * </ul>
     *
     * <pre>{@code
     * RetryPolicy policy = RetryPolicy.exponential(Duration.ofMillis(50))
     *     .maxAttempts(5)
     *     .maxDelay(Duration.ofSeconds(10))
     *     .withJitter(0.2)
     *     .retryWhen(e -> e instanceof IOException);
     *
     * effect.retry(policy);
     * }</pre>
     *
     * @param policy the retry policy to apply
     */
    default Effect<Throwable, A> retry(RetryPolicy policy) {
        return retryLoop(this, policy, 0);
    }

    private static <E extends Throwable, A> Effect<Throwable, A> retryLoop(
            Effect<E, A> effect,
            RetryPolicy policy,
            int retriesSoFar
    ) {
        return effect.widen().catchAll(error -> {
            if (!policy.shouldRetry(error, retriesSoFar)) {
                return Effect.fail(error);
            }
            Duration delay = policy.computeDelay(retriesSoFar);
            Effect<Throwable, ?> wait = delay.isZero()
                    ? Effect.unit()
                    : Effect.sleep(delay);
            return wait.flatMap(__ -> retryLoop(effect, policy, retriesSoFar + 1));
        });
    }

    /**
     * Fail with {@link com.cajunsystems.roux.exception.TimeoutException} if this effect does not
     * complete within {@code duration}. The error type widens to {@link Throwable}.
     */
    default Effect<Throwable, A> timeout(Duration duration) {
        return new Timeout<>(this, duration);
    }

    // -----------------------------------------------------------------------
    // Concurrency combinators
    // -----------------------------------------------------------------------

    default Effect<Throwable, Fiber<E, A>> fork() {
        return new Fork<>(this);
    }

    default Effect<Throwable, Fiber<E, A>> forkIn(EffectScope scope) {
        return scope.fork(this);
    }

    default <B, C> Effect<Throwable, C> zipPar(Effect<E, B> other, BiFunction<A, B, C> f) {
        return this.fork().flatMap(fiberA ->
            other.fork().flatMap(fiberB ->
                fiberA.join().mapError(e -> (Throwable) e).flatMap(a ->
                    fiberB.join().mapError(e -> (Throwable) e).map(b ->
                        f.apply(a, b)
                    )
                )
            )
        );
    }
}
