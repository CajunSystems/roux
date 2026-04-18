package com.cajunsystems.roux;

import com.cajunsystems.roux.data.Either;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
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
     * Apply {@code f} to each element of {@code items} to produce effects, run all
     * resulting effects in parallel, and collect results in order.
     * Fails fast on the first error.
     */
    public static <E extends Throwable, A, B> Effect<Throwable, List<B>> parTraverse(
            List<A> items,
            Function<A, Effect<E, B>> f
    ) {
        if (items.isEmpty()) {
            return Effect.succeed(List.of());
        }
        List<Effect<E, B>> effects = new ArrayList<>(items.size());
        for (A item : items) {
            effects.add(f.apply(item));
        }
        return parAll(effects);
    }

    /**
     * Like {@link #parTraverse} but wraps each result in {@link Either}, collecting
     * both successes and failures rather than short-circuiting on the first error.
     */
    public static <E extends Throwable, A, B> Effect<Throwable, List<Either<E, B>>> parTraverseEither(
            List<A> items,
            Function<A, Effect<E, B>> f
    ) {
        return parTraverse(items, item -> f.apply(item).attempt());
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
    // Executor interop
    // -----------------------------------------------------------------------

    /**
     * Submit a {@link Callable} to an existing {@link ExecutorService} and lift
     * the result into an effect. The submission happens when the effect is run.
     *
     * <p>Useful when a team has a tuned thread pool for CPU-bound work and wants
     * to keep it while adopting roux elsewhere.
     *
     * <pre>{@code
     * Effect<Throwable, Integer> heavy = Effects.fromExecutor(cpuPool, () -> expensiveCompute());
     * }</pre>
     */
    public static <A> Effect<Throwable, A> fromExecutor(ExecutorService executor, Callable<A> task) {
        return Effect.suspend(() -> {
            java.util.concurrent.Future<A> future = executor.submit(task);
            try {
                return future.get();
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception ex) throw ex;
                throw new RuntimeException(cause);
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new com.cajunsystems.roux.exception.CancelledException(e);
            }
        });
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
