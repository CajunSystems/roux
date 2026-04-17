package com.cajunsystems.roux.testing;

import com.cajunsystems.roux.capability.Capability;
import com.cajunsystems.roux.capability.CapabilityHandler;
import com.cajunsystems.roux.data.ThrowingFunction;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A test-friendly {@link CapabilityHandler} that records every invocation
 * and provides a fluent verification API.
 *
 * <p>Usage:
 * <pre>{@code
 * TestCapabilityHandler handler = TestCapabilityHandler.builder()
 *     .stub(FetchUser.class, req -> new User(req.userId(), "Alice"))
 *     .stub(DeleteUser.class, req -> true)
 *     .build();
 *
 * runtime.unsafeRunWithHandler(effect, handler);
 *
 * handler.verify(FetchUser.class).calledOnce();
 * handler.verify(DeleteUser.class).calledWith(req -> req.userId().equals("u1"));
 * handler.verify(SendEmail.class).neverCalled();
 * }</pre>
 *
 * <p>Stubs dispatch on exact capability class (same semantics as
 * {@link CapabilityHandler#builder()}). Unregistered capabilities throw
 * {@link UnsupportedOperationException}.
 */
public final class TestCapabilityHandler implements CapabilityHandler<Capability<?>> {

    private final Map<Class<?>, ThrowingFunction<?, ?>> stubs;
    private final Map<Class<?>, List<Object>> calls = new ConcurrentHashMap<>();

    private TestCapabilityHandler(Map<Class<?>, ThrowingFunction<?, ?>> stubs) {
        this.stubs = Map.copyOf(stubs);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <R> R handle(Capability<?> capability) throws Exception {
        Class<?> type = capability.getClass();
        ThrowingFunction<Object, R> stub = (ThrowingFunction<Object, R>) stubs.get(type);
        if (stub == null) {
            throw new UnsupportedOperationException(
                    "No stub registered for capability: " + type.getName());
        }
        calls.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(capability);
        return stub.apply(capability);
    }

    /**
     * Return a {@link CapabilityVerifier} for all recorded invocations of
     * the given capability type. Works even if no calls were recorded
     * (returns a verifier with an empty call list).
     */
    @SuppressWarnings("unchecked")
    public <C extends Capability<?>> CapabilityVerifier<C> verify(Class<C> type) {
        List<C> recorded = (List<C>) (List<?>) calls.getOrDefault(type, List.of());
        return new CapabilityVerifier<>(type.getSimpleName(), recorded);
    }

    /** Return a fluent builder for constructing a {@code TestCapabilityHandler}. */
    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for {@link TestCapabilityHandler}. */
    public static final class Builder {

        private final Map<Class<?>, ThrowingFunction<?, ?>> stubs = new HashMap<>();

        private Builder() {}

        /**
         * Register a stub for a specific capability type.
         *
         * @param type    exact capability class (not a supertype)
         * @param handler stub lambda returning the canned response
         */
        public <C extends Capability<R>, R> Builder stub(
                Class<C> type,
                ThrowingFunction<C, R> handler
        ) {
            stubs.put(type, handler);
            return this;
        }

        /** Build the {@link TestCapabilityHandler}. */
        public TestCapabilityHandler build() {
            return new TestCapabilityHandler(stubs);
        }
    }
}
