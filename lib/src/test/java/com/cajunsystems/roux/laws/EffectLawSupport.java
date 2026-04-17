package com.cajunsystems.roux.laws;

import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.EffectRuntime;
import com.cajunsystems.roux.runtime.DefaultEffectRuntime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Utilities for verifying algebraic laws on Effect&lt;E,A&gt;.
 *
 * <p>Effect equivalence is defined observationally: two effects are equivalent
 * if running them produces the same outcome — same success value, or same
 * error class and message.
 *
 * <p>Structural equality is not used because Effects are lazy descriptions;
 * two structurally different effect chains can be observationally equivalent.
 */
public final class EffectLawSupport {

    private EffectLawSupport() {}

    /**
     * Assert that two effects are observationally equivalent under a fresh runtime.
     * Uses DefaultEffectRuntime.create() for execution.
     */
    public static <E extends Throwable, A> void assertEquivalent(
            String lawName,
            Effect<E, A> left,
            Effect<E, A> right
    ) {
        try (DefaultEffectRuntime runtime = DefaultEffectRuntime.create()) {
            assertEquivalentWith(lawName, left, right, runtime);
        }
    }

    /**
     * Assert equivalence using a provided runtime (useful for parameterized law suites).
     */
    public static <E extends Throwable, A> void assertEquivalentWith(
            String lawName,
            Effect<E, A> left,
            Effect<E, A> right,
            EffectRuntime runtime
    ) {
        Outcome<A> leftOutcome = runCapturing(runtime, left);
        Outcome<A> rightOutcome = runCapturing(runtime, right);
        assertOutcomesMatch(lawName, leftOutcome, rightOutcome);
    }

    // -- Internals --

    sealed interface Outcome<A> {
        record Success<A>(A value) implements Outcome<A> {}
        record Failure<A>(Throwable error) implements Outcome<A> {}
    }

    private static <E extends Throwable, A> Outcome<A> runCapturing(
            EffectRuntime runtime,
            Effect<E, A> effect
    ) {
        try {
            return new Outcome.Success<>(runtime.unsafeRun(effect));
        } catch (Throwable e) {
            return new Outcome.Failure<>(e);
        }
    }

    private static <A> void assertOutcomesMatch(
            String lawName,
            Outcome<A> left,
            Outcome<A> right
    ) {
        if (left instanceof Outcome.Success<A> ls && right instanceof Outcome.Success<A> rs) {
            assertEquals(ls.value(), rs.value(),
                    "Law [" + lawName + "] violated: success values differ"
                    + " — left=" + ls.value() + ", right=" + rs.value());
        } else if (left instanceof Outcome.Failure<A> lf && right instanceof Outcome.Failure<A> rf) {
            assertEquals(lf.error().getClass(), rf.error().getClass(),
                    "Law [" + lawName + "] violated: error types differ"
                    + " — left=" + lf.error().getClass().getSimpleName()
                    + ", right=" + rf.error().getClass().getSimpleName());
            assertEquals(lf.error().getMessage(), rf.error().getMessage(),
                    "Law [" + lawName + "] violated: error messages differ"
                    + " — left=" + lf.error().getMessage()
                    + ", right=" + rf.error().getMessage());
        } else {
            fail("Law [" + lawName + "] violated: one effect succeeded and the other failed"
                    + " — left=" + left + ", right=" + right);
        }
    }
}
