package com.cajunsystems.roux.capability;

import com.cajunsystems.roux.data.ThrowingFunction;

/**
 * A typed capability environment that provides handlers for capabilities encoded in {@code R}.
 *
 * <p>{@code R} is a phantom type that tracks <em>which</em> capabilities this environment
 * satisfies.  It is either:
 * <ul>
 *   <li>a concrete capability interface (e.g. {@code DbOps}), or</li>
 *   <li>a combination built with {@link With} (e.g. {@code With<DbOps, EmailOps>}).</li>
 * </ul>
 *
 * <p>At runtime the environment is backed by a standard
 * {@link CapabilityHandler}{@code <Capability<?>>}; the phantom type adds the compile-time
 * proof that all required capabilities are covered.
 *
 * <h2>Creating environments</h2>
 * <pre>{@code
 * // Single-capability environment
 * HandlerEnv<DbOps> dbEnv = HandlerEnv.of(DbOps.class, cap -> switch (cap) {
 *     case DbOps.Query  q -> executeQuery(q.sql());
 *     case DbOps.Execute e -> { executeUpdate(e.sql()); yield null; }
 * });
 *
 * // Two-capability environment
 * HandlerEnv<EmailOps> emailEnv = HandlerEnv.of(EmailOps.class, cap -> ...);
 * HandlerEnv<With<DbOps, EmailOps>> full = dbEnv.and(emailEnv);
 * }</pre>
 *
 * @param <R> phantom type encoding the set of capabilities this environment provides
 * @see With
 * @see Empty
 * @see Layer
 */
public interface HandlerEnv<R> {

    /** Returns the underlying untyped handler for interop with the existing runtime. */
    CapabilityHandler<Capability<?>> toHandler();

    // -----------------------------------------------------------------------
    // Composition
    // -----------------------------------------------------------------------

    /**
     * Combine this environment with another, producing one that handles both.
     *
     * <p>The resulting phantom type {@code With<R, R2>} statically records that
     * capabilities from both environments are available.  At runtime capabilities
     * are tried against each backing handler in declaration order.
     *
     * <pre>{@code
     * HandlerEnv<With<DbOps, EmailOps>> full = dbEnv.and(emailEnv);
     * }</pre>
     *
     * @param other the environment to merge with this one
     * @param <R2>  phantom type of the other environment
     * @return a combined environment covering both {@code R} and {@code R2}
     */
    default <R2> HandlerEnv<With<R, R2>> and(HandlerEnv<R2> other) {
        HandlerEnv<R> self = this;
        return () -> CapabilityHandler.compose(self.toHandler(), other.toHandler());
    }

    // -----------------------------------------------------------------------
    // Factory methods
    // -----------------------------------------------------------------------

    /**
     * Create a typed environment for a single capability family.
     *
     * <p>The {@code type} argument is the capability interface (or class) to register;
     * subtypes (e.g. records inside a sealed interface) are resolved via the existing
     * {@link CapabilityHandler.Builder} interface-walk logic.
     *
     * <p>The F-bound {@code C extends Capability<R>} ensures the handler lambda's return
     * type matches the result type declared by the capability — no wildcards required.
     *
     * @param type    the root capability interface
     * @param handler lambda whose return type matches the capability's declared result type
     * @param <R>     the capability's result type, inferred from {@code C}
     * @param <C>     the concrete capability type
     * @return a typed environment providing {@code C}
     */
    static <R, C extends Capability<R>> HandlerEnv<C> of(
            Class<C> type,
            ThrowingFunction<C, R> handler
    ) {
        CapabilityHandler<Capability<?>> h = CapabilityHandler.builder()
                .on(type, handler)
                .build();
        return () -> h;
    }

    /**
     * Create a typed environment from a pre-built {@link CapabilityHandler}.
     *
     * <p>This is an escape hatch for interop with handlers built using the existing API
     * (e.g. {@link CompositeCapabilityHandler} subclasses or {@link CapabilityHandler#builder()}).
     * The phantom type {@code R} must accurately reflect what {@code handler} actually handles —
     * this correspondence is not verified at runtime.
     *
     * @param handler the underlying untyped handler
     * @param <R>     phantom type reflecting the capabilities handled
     * @return a typed wrapper around the existing handler
     */
    @SuppressWarnings("unchecked")
    static <R> HandlerEnv<R> fromHandler(CapabilityHandler<?> handler) {
        CapabilityHandler<Capability<?>> wide = (CapabilityHandler<Capability<?>>) handler;
        return () -> wide;
    }

    /**
     * The empty environment — satisfies no capabilities.
     *
     * <p>Useful as the seed value when building an environment from {@link Layer}s:
     * <pre>{@code
     * HandlerEnv<With<DbOps, EmailOps>> env =
     *     appLayer.build(HandlerEnv.empty())   // Effect<E, HandlerEnv<With<DbOps, EmailOps>>>
     *             .unsafeRun(runtime);
     * }</pre>
     *
     * @return an empty environment typed as {@link Empty}
     */
    static HandlerEnv<Empty> empty() {
        CapabilityHandler<Capability<?>> h = new CapabilityHandler<>() {
            @Override
            public <R> R handle(Capability<?> cap) {
                throw new UnsupportedOperationException(
                        "No handler in empty environment for: " + cap.getClass().getName()
                );
            }
        };
        return () -> h;
    }
}
