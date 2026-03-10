package com.cajunsystems.roux.capability;

import com.cajunsystems.roux.data.ThrowingFunction;

/**
 * A typed capability environment that wraps a {@link CapabilityHandler} and tracks,
 * at compile time, which capabilities it covers via the phantom type parameter {@code R}.
 *
 * <p>The phantom type {@code R} is erased at runtime — {@code HandlerEnv} is a
 * single-field wrapper with zero overhead beyond the underlying handler.
 *
 * <h3>Creating environments</h3>
 * <pre>{@code
 * // Single capability family — R must be consistent across all variants
 * HandlerEnv<StoreOps> storeEnv = HandlerEnv.of(StoreOps.class, cap -> switch (cap) {
 *     case StoreOps.Get g  -> store.getOrDefault(g.key(), "missing");
 *     case StoreOps.Put p  -> { store.put(p.key(), p.value()); yield "ok"; }
 * });
 * }</pre>
 *
 * <h3>Combining environments</h3>
 * <pre>{@code
 * HandlerEnv<With<StoreOps, LogOps>> full = storeEnv.and(logEnv);
 * }</pre>
 *
 * <h3>Running effects</h3>
 * <pre>{@code
 * runtime.unsafeRunWithHandler(effect, env.toHandler());
 * // Phase 8 will add: effectWithEnv.run(env, runtime)
 * }</pre>
 */
public final class HandlerEnv<R> {

    private final CapabilityHandler<Capability<?>> handler;

    private HandlerEnv(CapabilityHandler<Capability<?>> handler) {
        this.handler = handler;
    }

    /**
     * Return the underlying handler. Use this to pass the environment to
     * {@code EffectRuntime.unsafeRunWithHandler} or to {@code EffectWithEnv.run}.
     */
    public CapabilityHandler<Capability<?>> toHandler() {
        return handler;
    }

    /**
     * Create an environment for a single capability family using an F-bounded factory.
     *
     * <p>The F-bound {@code C extends Capability<R>} ensures that {@code handler} returns
     * exactly the type {@code R} that the capability declares — the compiler rejects handlers
     * that return the wrong type.
     *
     * <pre>{@code
     * // StoreOps extends Capability<String> — handler must return String
     * HandlerEnv<StoreOps> env = HandlerEnv.of(StoreOps.class, cap -> switch (cap) {
     *     case StoreOps.Get g -> store.get(g.key());
     *     case StoreOps.Put p -> { store.put(p.key(), p.value()); yield "ok"; }
     * });
     *
     * // Compile error — int is not String:
     * // HandlerEnv.of(StoreOps.class, cap -> 42);
     * }</pre>
     *
     * @param type    the capability family class (exact type, not a supertype)
     * @param handler lambda dispatching on the capability's concrete subtypes
     */
    public static <R, C extends Capability<R>> HandlerEnv<C> of(
            Class<C> type,
            ThrowingFunction<C, R> handler
    ) {
        return new HandlerEnv<>(CapabilityHandler.builder().on(type, handler).build());
    }

    /**
     * Combine this environment with {@code other}, producing an environment
     * that covers both {@code R} and {@code S}.
     *
     * <p>This environment is tried first; {@code other} is the fallback.
     * The phantom type {@code With<R, S>} tracks the combination at compile time.
     *
     * <pre>{@code
     * HandlerEnv<With<StoreOps, LogOps>> full = storeEnv.and(logEnv);
     * }</pre>
     */
    public <S> HandlerEnv<With<R, S>> and(HandlerEnv<S> other) {
        return new HandlerEnv<>(this.handler.orElse(other.handler));
    }

    /**
     * Wrap an existing {@link CapabilityHandler} with a declared phantom type.
     * Use when migrating from an untyped handler or when the F-bound on {@link #of}
     * cannot be satisfied (e.g., capability families with mixed return types).
     *
     * <p>The caller is responsible for ensuring that {@code handler} actually covers
     * the capabilities implied by {@code R}.
     */
    @SuppressWarnings("unchecked")
    public static <R> HandlerEnv<R> fromHandler(CapabilityHandler<Capability<?>> handler) {
        return (HandlerEnv<R>) new HandlerEnv<>(handler);
    }

    /**
     * An empty environment covering no capabilities.
     * Use with effects that have {@link Empty} requirements.
     *
     * <pre>{@code
     * HandlerEnv<Empty> env = HandlerEnv.empty();
     * }</pre>
     */
    public static HandlerEnv<Empty> empty() {
        CapabilityHandler<Capability<?>> noOp = new CapabilityHandler<>() {
            @Override
            public <R> R handle(Capability<?> cap) {
                throw new UnsupportedOperationException(
                        "No handler registered for capability: " + cap.getClass().getName());
            }
        };
        return new HandlerEnv<>(noOp);
    }
}
