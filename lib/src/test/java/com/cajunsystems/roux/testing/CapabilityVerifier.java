package com.cajunsystems.roux.testing;

import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fluent verification API for recorded capability invocations.
 *
 * <p>Returned by {@link TestCapabilityHandler#verify(Class)}. All methods
 * return {@code this} for chaining.
 *
 * <pre>{@code
 * handler.verify(FetchUser.class)
 *     .calledOnce()
 *     .calledWith(req -> req.userId().equals("alice"));
 * }</pre>
 */
public final class CapabilityVerifier<C> {

    private final String typeName;
    private final List<C> calls;

    CapabilityVerifier(String typeName, List<C> calls) {
        this.typeName = typeName;
        this.calls = List.copyOf(calls);
    }

    /** Assert this capability was called exactly once. */
    public CapabilityVerifier<C> calledOnce() {
        assertEquals(1, calls.size(),
                typeName + " expected to be called once but was called " + calls.size() + " time(s)");
        return this;
    }

    /** Assert this capability was never called. */
    public CapabilityVerifier<C> neverCalled() {
        assertEquals(0, calls.size(),
                typeName + " expected to never be called but was called " + calls.size() + " time(s)");
        return this;
    }

    /** Assert this capability was called exactly {@code expected} times. */
    public CapabilityVerifier<C> calledTimes(int expected) {
        assertEquals(expected, calls.size(),
                typeName + " expected to be called " + expected + " time(s) but was called " + calls.size() + " time(s)");
        return this;
    }

    /**
     * Assert that at least one invocation matched the given predicate.
     * Useful for checking argument values.
     */
    public CapabilityVerifier<C> calledWith(Predicate<C> predicate) {
        assertTrue(calls.stream().anyMatch(predicate),
                typeName + " was not called with a matching argument. Total calls: " + calls.size());
        return this;
    }
}
