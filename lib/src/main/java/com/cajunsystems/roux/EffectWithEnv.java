package com.cajunsystems.roux;

import com.cajunsystems.roux.capability.Empty;
import com.cajunsystems.roux.capability.HandlerEnv;

import java.util.function.Function;

/**
 * A thin wrapper around {@link Effect}{@code <E, A>} that statically declares,
 * via the phantom type parameter {@code R}, which capability environment the effect needs.
 *
 * <p>{@code R} is erased at runtime — {@code EffectWithEnv} is a single-field wrapper
 * with zero overhead beyond the underlying effect.
 *
 * <h3>Declaring requirements</h3>
 * <pre>{@code
 * // R must be specified explicitly — Java cannot infer phantom types
 * EffectWithEnv<StoreOps, Throwable, String> get =
 *     EffectWithEnv.<StoreOps, Throwable, String>of(new StoreOps.Get("key").toEffect());
 *
 * // For effects with no capability requirements:
 * EffectWithEnv<Empty, RuntimeException, Integer> constant =
 *     EffectWithEnv.pure(Effect.succeed(42));
 * }</pre>
 *
 * <h3>Running</h3>
 * <pre>{@code
 * HandlerEnv<StoreOps> env = HandlerEnv.of(StoreOps.class, cap -> store.get(cap.key()));
 *
 * // Compiles — env covers StoreOps
 * String value = get.run(env, runtime);
 *
 * // Does NOT compile — HandlerEnv<LogOps> is not HandlerEnv<StoreOps>
 * // get.run(logEnv, runtime);
 * }</pre>
 *
 * <h3>Transforming</h3>
 * <pre>{@code
 * EffectWithEnv<StoreOps, Throwable, Integer> length = get.map(String::length);
 *
 * EffectWithEnv<StoreOps, Throwable, String> chained =
 *     get.flatMap(v -> EffectWithEnv.of(new StoreOps.Put("last", v).toEffect()));
 * }</pre>
 */
public final class EffectWithEnv<R, E extends Throwable, A> {

    private final Effect<E, A> effect;

    private EffectWithEnv(Effect<E, A> effect) {
        this.effect = effect;
    }

    /**
     * Wrap {@code effect} with declared capability requirements {@code R}.
     *
     * <p>The caller is responsible for asserting that {@code effect} only uses
     * capabilities covered by {@code R}. Java cannot infer phantom types, so
     * {@code R} must be specified explicitly at the call site:
     * <pre>{@code
     * EffectWithEnv.<StoreOps, Throwable, String>of(new StoreOps.Get("k").toEffect())
     * }</pre>
     */
    public static <R, E extends Throwable, A> EffectWithEnv<R, E, A> of(Effect<E, A> effect) {
        return new EffectWithEnv<>(effect);
    }

    /**
     * Wrap {@code effect} with {@link Empty} requirements — no capabilities needed.
     *
     * <pre>{@code
     * EffectWithEnv<Empty, RuntimeException, Integer> constant =
     *     EffectWithEnv.pure(Effect.succeed(42));
     * constant.run(HandlerEnv.empty(), runtime);
     * }</pre>
     */
    public static <E extends Throwable, A> EffectWithEnv<Empty, E, A> pure(Effect<E, A> effect) {
        return new EffectWithEnv<>(effect);
    }

    /**
     * Transform the result value, preserving the capability requirement {@code R}.
     */
    public <B> EffectWithEnv<R, E, B> map(Function<A, B> f) {
        return new EffectWithEnv<>(effect.map(f));
    }

    /**
     * Chain to another {@code EffectWithEnv} that requires the same environment {@code R},
     * preserving the capability requirement.
     */
    public <B> EffectWithEnv<R, E, B> flatMap(Function<A, EffectWithEnv<R, E, B>> f) {
        return new EffectWithEnv<>(effect.flatMap(a -> f.apply(a).effect));
    }

    /**
     * Run this effect using the provided environment.
     *
     * <p>The compiler verifies that {@code env} covers exactly {@code R} — the same
     * capability set this effect declared. Passing a {@code HandlerEnv<LogOps>} for an
     * {@code EffectWithEnv<StoreOps, ...>} is a compile error.
     *
     * @param env     the capability environment that satisfies {@code R}
     * @param runtime the effect runtime to execute with
     * @return the computed value
     * @throws E if the effect fails
     */
    public A run(HandlerEnv<R> env, EffectRuntime runtime) throws E {
        return runtime.unsafeRunWithHandler(effect, env.toHandler());
    }

    /**
     * Return the underlying {@link Effect} for interop with untyped APIs.
     */
    public Effect<E, A> effect() {
        return effect;
    }
}
