package com.cajunsystems.roux;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

public final class Effects {

    private Effects() {
        throw new UnsupportedOperationException("Utility class");
    }

    // -----------------------------------------------------------------------
    // Parallel combinators (fixed-arity)
    // -----------------------------------------------------------------------

    public static <E extends Throwable, A, B, R> Effect<Throwable, R> par(
            Effect<E, A> ea,
            Effect<E, B> eb,
            BiFunction<A, B, R> f
    ) {
        return ea.zipPar(eb, f);
    }

    public static <E extends Throwable, A, B, C, R> Effect<Throwable, R> par(
            Effect<E, A> ea,
            Effect<E, B> eb,
            Effect<E, C> ec,
            Function3<A, B, C, R> f
    ) {
        return ea.fork().flatMap(fa ->
                eb.fork().flatMap(fb ->
                        ec.fork().flatMap(fc ->
                                fa.join().mapError(e -> (Throwable) e).flatMap(a ->
                                        fb.join().mapError(e -> (Throwable) e).flatMap(b ->
                                                fc.join().mapError(e -> (Throwable) e).map(c ->
                                                        f.apply(a, b, c)
                                                )
                                        )
                                )
                        )
                )
        );
    }

    public static <E extends Throwable, A, B, C, D, R> Effect<Throwable, R> par(
            Effect<E, A> ea,
            Effect<E, B> eb,
            Effect<E, C> ec,
            Effect<E, D> ed,
            Function4<A, B, C, D, R> f
    ) {
        return ea.fork().flatMap(fa ->
                eb.fork().flatMap(fb ->
                        ec.fork().flatMap(fc ->
                                ed.fork().flatMap(fd ->
                                        fa.join().mapError(e -> (Throwable) e).flatMap(a ->
                                                fb.join().mapError(e -> (Throwable) e).flatMap(b ->
                                                        fc.join().mapError(e -> (Throwable) e).flatMap(c ->
                                                                fd.join().mapError(e -> (Throwable) e).map(d ->
                                                                        f.apply(a, b, c, d)
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                )
        );
    }

    // -----------------------------------------------------------------------
    // Variable-arity parallel (list-based)
    // -----------------------------------------------------------------------

    /**
     * Run a list of effects in parallel and collect all results in order.
     * If any effect fails the first failure is propagated (others are left to
     * complete or be interrupted by the runtime).
     *
     * @param effects non-empty list of effects to run concurrently
     */
    public static <E extends Throwable, A> Effect<Throwable, List<A>> parAll(
            List<Effect<E, A>> effects
    ) {
        if (effects.isEmpty()) {
            return Effect.succeed(List.of());
        }

        // Fork all effects
        @SuppressWarnings("unchecked")
        Effect<Throwable, Fiber<E, A>>[] forkEffects = new Effect[effects.size()];
        for (int i = 0; i < effects.size(); i++) {
            forkEffects[i] = effects.get(i).fork();
        }

        // Build a chain that forks all and then joins all in order
        Effect<Throwable, List<Fiber<E, A>>> fibersEffect = Effect.succeed(new ArrayList<>());
        for (Effect<Throwable, Fiber<E, A>> forkEff : forkEffects) {
            fibersEffect = fibersEffect.flatMap(fibers ->
                    forkEff.map(fiber -> {
                        List<Fiber<E, A>> next = new ArrayList<>(fibers);
                        next.add(fiber);
                        return next;
                    })
            );
        }

        return fibersEffect.flatMap(fibers -> {
            Effect<Throwable, List<A>> joinEffect = Effect.succeed(new ArrayList<>());
            for (Fiber<E, A> fiber : fibers) {
                joinEffect = joinEffect.flatMap(results ->
                        fiber.join().mapError(e -> (Throwable) e).map(result -> {
                            List<A> next = new ArrayList<>(results);
                            next.add(result);
                            return next;
                        })
                );
            }
            // Use unmodifiableList rather than List.copyOf to allow null elements
            // (e.g. Void-returning effects).
            return joinEffect.map(results -> Collections.unmodifiableList(new ArrayList<>(results)));
        });
    }

    // -----------------------------------------------------------------------
    // Race
    // -----------------------------------------------------------------------

    /**
     * Race a list of effects against each other, returning the result of
     * whichever completes first (success or failure). The remaining effects are
     * cancelled.
     *
     * @param effects list of effects to race; must be non-empty
     */
    @SuppressWarnings("unchecked")
    public static <E extends Throwable, A> Effect<Throwable, A> race(
            List<Effect<E, A>> effects
    ) {
        return new Effect.Race<>((List<Effect<E, A>>) List.copyOf(effects));
    }

    /** Convenience overload for two effects. */
    public static <E extends Throwable, A> Effect<Throwable, A> race(
            Effect<E, A> first,
            Effect<E, A> second
    ) {
        return race(List.of(first, second));
    }

    // -----------------------------------------------------------------------
    // Sequence / Traverse
    // -----------------------------------------------------------------------

    /**
     * Run each effect in the list <em>sequentially</em> and collect all results.
     * Short-circuits on the first failure.
     */
    public static <E extends Throwable, A> Effect<E, List<A>> sequence(
            List<Effect<E, A>> effects
    ) {
        Effect<E, List<A>> acc = Effect.succeed(new ArrayList<>());
        for (Effect<E, A> effect : effects) {
            acc = acc.flatMap(results ->
                    effect.map(result -> {
                        List<A> next = new ArrayList<>(results);
                        next.add(result);
                        return next;
                    })
            );
        }
        return acc.map(results -> Collections.unmodifiableList(new ArrayList<>(results)));
    }

    /**
     * Apply {@code f} to each element of {@code items} to produce effects, run
     * them <em>sequentially</em>, and collect all results.
     */
    public static <E extends Throwable, A, B> Effect<E, List<B>> traverse(
            List<A> items,
            Function<A, Effect<E, B>> f
    ) {
        Effect<E, List<B>> acc = Effect.succeed(new ArrayList<>());
        for (A item : items) {
            acc = acc.flatMap(results ->
                    f.apply(item).map(result -> {
                        List<B> next = new ArrayList<>(results);
                        next.add(result);
                        return next;
                    })
            );
        }
        return acc.map(results -> Collections.unmodifiableList(new ArrayList<>(results)));
    }

    // -----------------------------------------------------------------------
    // Functional types
    // -----------------------------------------------------------------------

    @FunctionalInterface
    public interface Function3<A, B, C, R> {
        R apply(A a, B b, C c);
    }

    @FunctionalInterface
    public interface Function4<A, B, C, D, R> {
        R apply(A a, B b, C c, D d);
    }

    public record Tuple2<A, B>(A first, B second) {}

    public record Tuple3<A, B, C>(A first, B second, C third) {}
}
