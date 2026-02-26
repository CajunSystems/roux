package com.cajunsystems.roux.capability;

import com.cajunsystems.roux.data.ThrowingFunction;

import java.util.HashMap;
import java.util.Map;

/**
 * Interprets {@link Capability} values and performs the actual side effects they
 * describe. Handlers are {@code @FunctionalInterface}s so they can be expressed
 * as lambdas when handling a single, concrete capability type.
 *
 * <p>Multiple handlers can be combined via {@link #compose} or chained with
 * {@link #orElse}. Both methods use <em>type-based dispatch</em> rather than
 * relying on {@link ClassCastException} as a control-flow mechanism.
 */
@FunctionalInterface
public interface CapabilityHandler<C extends Capability<?>> {

    <R> R handle(C capability) throws Exception;

    // -----------------------------------------------------------------------
    // Type widening
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    default CapabilityHandler<Capability<?>> widen() {
        return (CapabilityHandler<Capability<?>>) this;
    }

    // -----------------------------------------------------------------------
    // Composition
    // -----------------------------------------------------------------------

    /**
     * Returns a handler that first tries {@code this}, and if the capability
     * type is not handled (i.e. an {@link UnsupportedOperationException} is
     * thrown), delegates to {@code fallback}.
     *
     * <p>This uses explicit {@link UnsupportedOperationException} as the
     * "not handled" signal rather than relying on {@link ClassCastException}.
     */
    default CapabilityHandler<Capability<?>> orElse(CapabilityHandler<?> fallback) {
        @SuppressWarnings("unchecked")
        CapabilityHandler<Capability<?>> self = (CapabilityHandler<Capability<?>>) this;
        @SuppressWarnings("unchecked")
        CapabilityHandler<Capability<?>> fallbackWide = (CapabilityHandler<Capability<?>>) fallback;

        return new CapabilityHandler<>() {
            @Override
            public <R> R handle(Capability<?> capability) throws Exception {
                try {
                    return self.handle(capability);
                } catch (UnsupportedOperationException e) {
                    return fallbackWide.handle(capability);
                }
            }
        };
    }

    /**
     * Compose multiple handlers into one. Each handler is tried in order; the
     * first one that does <em>not</em> throw {@link UnsupportedOperationException}
     * wins.
     *
     * <p>If no handler can service the capability an
     * {@link UnsupportedOperationException} is thrown with a descriptive message.
     */
    @SafeVarargs
    static CapabilityHandler<Capability<?>> compose(CapabilityHandler<?>... handlers) {
        return new CapabilityHandler<>() {
            @Override
            @SuppressWarnings("unchecked")
            public <R> R handle(Capability<?> capability) throws Exception {
                for (CapabilityHandler<?> handler : handlers) {
                    try {
                        return ((CapabilityHandler<Capability<?>>) handler).handle(capability);
                    } catch (UnsupportedOperationException ignored) {
                        // Try next handler
                    }
                }
                throw new UnsupportedOperationException(
                        "No handler found for capability: " + capability.getClass().getName()
                );
            }
        };
    }

    // -----------------------------------------------------------------------
    // Type-safe registry builder
    // -----------------------------------------------------------------------

    /**
     * Returns a fluent builder for constructing a capability handler that
     * dispatches on exact capability types without relying on exceptions.
     *
     * <pre>{@code
     * CapabilityHandler<Capability<?>> handler = CapabilityHandler.builder()
     *     .on(MyCapability.Fetch.class, fetch -> httpClient.get(fetch.url()))
     *     .on(MyCapability.Log.class,   log   -> { logger.info(log.message()); return null; })
     *     .build();
     * }</pre>
     */
    static Builder builder() {
        return new Builder();
    }

    final class Builder {
        private final Map<Class<?>, CapabilityHandler<?>> handlers = new HashMap<>();

        private Builder() {}

        /**
         * Register a handler for a specific capability type using a lambda-friendly
         * {@link ThrowingFunction}. The return value is cast to the caller's expected
         * type at runtime.
         *
         * <pre>{@code
         * CapabilityHandler.builder()
         *     .on(MyCapability.Fetch.class, fetch -> httpClient.get(fetch.url()))
         *     .on(MyCapability.Log.class,   log   -> { logger.info(log.msg()); return null; })
         *     .build();
         * }</pre>
         *
         * @param type    exact capability class (not a supertype)
         * @param handler handler lambda for that specific type
         */
        @SuppressWarnings("unchecked")
        public <C extends Capability<?>> Builder on(
                Class<C> type,
                ThrowingFunction<C, ?> handler
        ) {
            handlers.put(type, new CapabilityHandler<C>() {
                @Override
                public <R> R handle(C capability) throws Exception {
                    return (R) handler.apply(capability);
                }
            });
            return this;
        }

        /** Build the composed handler. */
        @SuppressWarnings("unchecked")
        public CapabilityHandler<Capability<?>> build() {
            Map<Class<?>, CapabilityHandler<?>> snapshot = Map.copyOf(handlers);
            return new CapabilityHandler<>() {
                @Override
                public <R> R handle(Capability<?> capability) throws Exception {
                    Class<?> type = capability.getClass();
                    CapabilityHandler<?> h = snapshot.get(type);
                    if (h != null) {
                        return ((CapabilityHandler<Capability<?>>) h).handle(capability);
                    }
                    // Walk interfaces in case the capability implements a registered interface
                    for (Class<?> iface : type.getInterfaces()) {
                        h = snapshot.get(iface);
                        if (h != null) {
                            return ((CapabilityHandler<Capability<?>>) h).handle(capability);
                        }
                    }
                    throw new UnsupportedOperationException(
                            "No handler registered for capability: " + type.getName()
                    );
                }
            };
        }
    }
}
