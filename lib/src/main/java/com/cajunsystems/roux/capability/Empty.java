package com.cajunsystems.roux.capability;

/**
 * Phantom type representing an empty capability environment — one that provides no handlers.
 *
 * <p>Used as the base case when building up environments with {@link With}.  A
 * {@link HandlerEnv}{@code <Empty>} contains no handlers and will throw
 * {@link UnsupportedOperationException} if any capability is performed against it.
 *
 * <p>In practice you will never create a value of type {@code Empty}; it only appears
 * as a type argument:
 * <pre>{@code
 * Layer<Empty, RuntimeException, DbOps> dbLayer = Layer.succeed(DbOps.class, cap -> ...);
 * HandlerEnv<With<DbOps, EmailOps>> env = dbLayer.and(emailLayer).build(HandlerEnv.empty());
 * }</pre>
 *
 * @see With
 * @see HandlerEnv#empty()
 */
public interface Empty {}
