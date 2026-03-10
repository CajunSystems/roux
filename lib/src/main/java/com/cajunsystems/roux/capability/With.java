package com.cajunsystems.roux.capability;

/**
 * Phantom type representing a capability environment that contains both {@code A} and {@code B}.
 *
 * <p>Never instantiated; exists only to carry type information at compile time.
 * Nest right-associatively for three or more capabilities:
 * <pre>{@code
 * HandlerEnv<With<DbOps, With<EmailOps, ConfigOps>>> env = ...;
 * }</pre>
 *
 * <p>Created automatically by {@link HandlerEnv#and(HandlerEnv)}:
 * <pre>{@code
 * HandlerEnv<With<StoreOps, LogOps>> full = storeEnv.and(logEnv);
 * }</pre>
 */
public interface With<A, B> {}
