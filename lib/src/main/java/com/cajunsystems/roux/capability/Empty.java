package com.cajunsystems.roux.capability;

/**
 * Phantom type representing an empty capability environment — no capabilities required.
 *
 * <p>Never instantiated; exists only to carry type information at compile time.
 * Used as the base case for {@link HandlerEnv}:
 * <pre>{@code
 * HandlerEnv<Empty> env = HandlerEnv.empty();
 * }</pre>
 */
public interface Empty {}
