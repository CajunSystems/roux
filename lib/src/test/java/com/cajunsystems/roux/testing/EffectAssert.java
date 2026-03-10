package com.cajunsystems.roux.testing;

import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fluent assertion chain for a single executed Effect outcome.
 *
 * <p>The effect is run once at construction time. All assertion methods
 * operate on the stored outcome — there is no re-execution.
 *
 * <p>All assertion methods return {@code this} for chaining, except
 * {@link #andReturn()} and {@link #andError()} which extract values.
 *
 * <p>Obtain instances via {@link EffectAssertions#assertThat}.
 */
public final class EffectAssert<E extends Throwable, A> {

    sealed interface Outcome<A> {
        record Success<A>(A value)         implements Outcome<A> {}
        record Failure<A>(Throwable error) implements Outcome<A> {}
    }

    private final Outcome<A> outcome;

    EffectAssert(Outcome<A> outcome) {
        this.outcome = outcome;
    }

    // -----------------------------------------------------------------------
    // Success assertions
    // -----------------------------------------------------------------------

    /** Assert the effect succeeded with any value. */
    public EffectAssert<E, A> succeeds() {
        if (outcome instanceof Outcome.Failure<A> f) {
            fail("Expected effect to succeed, but it failed with: "
                    + f.error().getClass().getSimpleName() + ": " + f.error().getMessage());
        }
        return this;
    }

    /** Assert the effect succeeded with the exact expected value. */
    public EffectAssert<E, A> succeedsWith(A expected) {
        succeeds();
        A actual = ((Outcome.Success<A>) outcome).value();
        assertEquals(expected, actual,
                "Effect succeeded but value does not match");
        return this;
    }

    /** Assert the effect succeeded and the value satisfies the given predicate. */
    public EffectAssert<E, A> succeedsWith(Predicate<A> predicate) {
        succeeds();
        A actual = ((Outcome.Success<A>) outcome).value();
        assertTrue(predicate.test(actual),
                "Effect succeeded with value=" + actual + " but predicate was not satisfied");
        return this;
    }

    // -----------------------------------------------------------------------
    // Failure assertions
    // -----------------------------------------------------------------------

    /** Assert the effect failed with any error. */
    public EffectAssert<E, A> fails() {
        if (outcome instanceof Outcome.Success<A> s) {
            fail("Expected effect to fail, but it succeeded with: " + s.value());
        }
        return this;
    }

    /** Assert the effect failed with an error of the given type (exact or subtype). */
    public EffectAssert<E, A> failsWith(Class<? extends Throwable> errorType) {
        fails();
        Throwable error = ((Outcome.Failure<A>) outcome).error();
        assertInstanceOf(errorType, error,
                "Effect failed but error type does not match — expected "
                + errorType.getSimpleName() + " but got " + error.getClass().getSimpleName());
        return this;
    }

    /** Assert the effect failed and the error satisfies the given predicate. */
    public EffectAssert<E, A> failsWith(Predicate<? super Throwable> predicate) {
        fails();
        Throwable error = ((Outcome.Failure<A>) outcome).error();
        assertTrue(predicate.test(error),
                "Effect failed with " + error.getClass().getSimpleName()
                + ": " + error.getMessage() + " but predicate was not satisfied");
        return this;
    }

    // -----------------------------------------------------------------------
    // Value extraction
    // -----------------------------------------------------------------------

    /**
     * Assert the effect succeeded and return the value for further custom assertions.
     */
    public A andReturn() {
        succeeds();
        return ((Outcome.Success<A>) outcome).value();
    }

    /**
     * Assert the effect failed and return the error for further custom assertions.
     */
    @SuppressWarnings("unchecked")
    public E andError() {
        fails();
        return (E) ((Outcome.Failure<A>) outcome).error();
    }
}
