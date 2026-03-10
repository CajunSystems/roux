package com.cajunsystems.roux.capability;

/**
 * Phantom type representing the union of two capability requirements {@code A} and {@code B}.
 *
 * <p>Used as a type argument to encode that an environment (or an effect's requirements) spans
 * two distinct capability families.  Nesting allows arbitrary numbers of capabilities to be
 * composed:
 * <pre>{@code
 * // Two capabilities
 * HandlerEnv<With<DbOps, EmailOps>> twoCapEnv = dbEnv.and(emailEnv);
 *
 * // Three capabilities — nest on either side; convention is right-nested
 * HandlerEnv<With<DbOps, With<EmailOps, ConfigOps>>> threeCapEnv = ...;
 * }</pre>
 *
 * <p>No instance of {@code With} is ever created; it exists purely to carry type information
 * at compile time.  The actual handler logic lives in the {@link HandlerEnv} that is
 * parameterised over it.
 *
 * @param <A> the first capability requirement
 * @param <B> the second capability requirement
 * @see HandlerEnv#and(HandlerEnv)
 * @see Empty
 */
public interface With<A, B> {}
