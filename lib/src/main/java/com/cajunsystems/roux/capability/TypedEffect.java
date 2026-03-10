package com.cajunsystems.roux.capability;

import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.EffectRuntime;

import java.util.function.Function;

/**
 * A wrapper around {@link Effect}{@code <E, A>} that statically declares which capability
 * environment {@code R} the effect requires in order to run.
 *
 * <p>The phantom type parameter {@code R} encodes the set of capabilities that must be provided
 * (via a matching {@link HandlerEnv}{@code <R>}) before the effect can be executed.  This is the
 * direct analog of the {@code R} environment parameter in ZIO's {@code ZIO[R, E, A]}.
 *
 * <p>The key guarantee: {@link #run} only compiles when the caller holds a
 * {@code HandlerEnv<R>} whose phantom type matches the effect's declared requirement.
 * If any capability is missing the program will not compile.
 *
 * <h2>Creating typed effects</h2>
 * <pre>{@code
 * // Declare the effect's capability requirement explicitly
 * TypedEffect<With<DbOps, EmailOps>, Exception, String> processOrder =
 *     TypedEffect.of(
 *         new DbOps.Query("SELECT * FROM orders WHERE id = ?")
 *             .toEffect()
 *             .flatMap(result -> new EmailOps.Send("ops@example.com", result)
 *                 .toEffect()
 *                 .map(__ -> result))
 *     );
 * }</pre>
 *
 * <h2>Running a typed effect</h2>
 * <pre>{@code
 * HandlerEnv<DbOps>   dbEnv    = HandlerEnv.of(DbOps.class,    cap -> ...);
 * HandlerEnv<EmailOps> emailEnv = HandlerEnv.of(EmailOps.class, cap -> ...);
 *
 * // Combine: type system tracks that both are present
 * HandlerEnv<With<DbOps, EmailOps>> fullEnv = dbEnv.and(emailEnv);
 *
 * // Compiles — fullEnv satisfies the requirement
 * String result = processOrder.run(fullEnv, runtime);
 *
 * // Does NOT compile — dbEnv alone does not satisfy With<DbOps, EmailOps>
 * // processOrder.run(dbEnv, runtime);   // ← compile error
 * }</pre>
 *
 * @param <R> phantom type encoding the required capability environment
 * @param <E> error type
 * @param <A> success type
 * @see HandlerEnv
 * @see Layer
 * @see With
 * @see Empty
 */
public final class TypedEffect<R, E extends Throwable, A> {

    private final Effect<E, A> effect;

    private TypedEffect(Effect<E, A> effect) {
        this.effect = effect;
    }

    // -----------------------------------------------------------------------
    // Factory methods
    // -----------------------------------------------------------------------

    /**
     * Wrap an untyped {@link Effect} with an explicit capability requirement declaration.
     *
     * <p>The caller is responsible for ensuring that the phantom type {@code R} accurately
     * reflects which capabilities the underlying effect performs — this relationship is not
     * checked at runtime.
     *
     * @param effect the underlying effect
     * @param <R>    the declared capability requirement
     * @param <E>    error type
     * @param <A>    success type
     * @return a typed effect
     */
    public static <R, E extends Throwable, A> TypedEffect<R, E, A> of(Effect<E, A> effect) {
        return new TypedEffect<>(effect);
    }

    /**
     * Wrap an untyped {@link Effect} that has no capability requirements.
     *
     * @param effect the underlying pure/suspended effect
     * @param <E>    error type
     * @param <A>    success type
     * @return a typed effect requiring an empty environment
     */
    public static <E extends Throwable, A> TypedEffect<Empty, E, A> pure(Effect<E, A> effect) {
        return new TypedEffect<>(effect);
    }

    // -----------------------------------------------------------------------
    // Combinators — all preserve the requirement type R
    // -----------------------------------------------------------------------

    /**
     * Transform the success value.
     *
     * @param f   the mapping function
     * @param <B> the new success type
     * @return a new typed effect with the same requirement
     */
    public <B> TypedEffect<R, E, B> map(Function<A, B> f) {
        return new TypedEffect<>(effect.map(f));
    }

    /**
     * Monadic bind — the continuation must share the same capability requirement.
     *
     * @param f   a function from success value to the next typed effect
     * @param <B> the next success type
     * @return a new typed effect with the same requirement
     */
    public <B> TypedEffect<R, E, B> flatMap(Function<A, TypedEffect<R, E, B>> f) {
        return new TypedEffect<>(effect.flatMap(a -> f.apply(a).effect));
    }

    /**
     * Access the underlying untyped effect for interop with the existing runtime API
     * or for effects that do not support the typed-handler path.
     *
     * @return the wrapped {@link Effect}
     */
    public Effect<E, A> toEffect() {
        return effect;
    }

    // -----------------------------------------------------------------------
    // Execution
    // -----------------------------------------------------------------------

    /**
     * Run this effect using the provided environment and runtime.
     *
     * <p>The compiler enforces that {@code env} is parameterised over exactly the same
     * capability set {@code R} that was declared when this {@code TypedEffect} was
     * created.  If any capability is absent the program will not compile.
     *
     * @param env     the capability environment; must be parameterised over {@code R}
     * @param runtime the effect runtime to use for execution
     * @return the success value
     * @throws E if the effect fails
     */
    public A run(HandlerEnv<R> env, EffectRuntime runtime) throws E {
        return runtime.unsafeRunWithHandler(effect, env.toHandler());
    }
}
