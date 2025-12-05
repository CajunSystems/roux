package com.cajunsystems.roux;

import com.cajunsystems.roux.data.Either;

import java.util.function.Function;
import java.util.function.Supplier;

public sealed interface Effect<E extends Throwable, A> {

    record Pure<E extends Throwable, A>(A value) implements Effect<E, A> {}
    record Fail<E extends Throwable, A>(E error) implements Effect<E, A> {}
    record Suspend<E extends Throwable, A>(Supplier<A> thunk) implements Effect<E, A> {}
    record FlatMap<E extends Throwable, A, B>(
            Effect<E, A> source,
            Function<A, Effect<E, B>> f
    ) implements Effect<E, B> {}

    static <E extends Throwable, A> Effect<E, A> succeed(A value) {
        return new Pure<>(value);
    }

    static <E extends Throwable, A> Effect<E, A> fail(E error) {
        return new Fail<>(error);
    }

    static <E extends Throwable, A> Effect<E, A> suspend(Supplier<A> thunk) {
        return new Suspend<>(thunk);
    }

    default <B> Effect<E, B> map(Function<A, B> f) {
        return flatMap(a -> succeed(f.apply(a)));
    }

    default <B> Effect<E, B> flatMap(Function<A, Effect<E, B>> f) {
        return new FlatMap<>(this, f);
    }

    record Fold<E extends Throwable, E2 extends Throwable, A, B>(
            Effect<E, A> source,
            Function<E, Effect<E2, B>> onError,
            Function<A, Effect<E2, B>> onSuccess
    ) implements Effect<E2, B> {}

    record MapError<E extends Throwable, E2 extends Throwable, A>(
            Effect<E, A> source,
            Function<E, E2> f
    ) implements Effect<E2, A> {}

    // Combinators
    default <E2 extends Throwable> Effect<E2, A> catchAll(
            Function<E, Effect<E2, A>> handler
    ) {
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
}