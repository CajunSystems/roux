package com.cajunsystems.roux;

import com.cajunsystems.roux.capability.CapabilityHandler;
import com.cajunsystems.roux.capability.Capability;
import com.cajunsystems.roux.data.Either;
import com.cajunsystems.roux.data.ThrowingSupplier;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

public sealed interface Effect<E extends Throwable, A> {

    // Primitive constructors
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

    // Smart constructors
    static <E extends Throwable, A> Effect<E, A> succeed(A value) {
        return new Pure<>(value);
    }

    static <E extends Throwable, A> Effect<E, A> fail(E error) {
        return new Fail<>(error);
    }

    static <E extends Throwable, A> Effect<E, A> suspend(ThrowingSupplier<A> thunk) {
        return new Suspend<>(thunk);
    }

    // Basic combinators
    default <B> Effect<E, B> map(Function<A, B> f) {
        return flatMap(a -> succeed(f.apply(a)));
    }

    default <B> Effect<E, B> flatMap(Function<A, Effect<E, B>> f) {
        return new FlatMap<>(this, f);
    }

    default Effect<E, A> catchAll(Function<E, Effect<E, A>> handler) {
        return new Fold<>(this, handler, Effect::succeed);
    }

    default <E2 extends Throwable> Effect<E2, A> mapError(Function<E, E2> f) {
        return new MapError<>(this, f);
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
}