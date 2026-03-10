package com.cajunsystems.roux.laws;

import com.cajunsystems.roux.Effect;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.function.Function;

import static com.cajunsystems.roux.laws.EffectLawSupport.assertEquivalent;

/**
 * Verifies functor laws for Effect&lt;E,A&gt;.
 *
 * <p>A functor must satisfy:
 * <ol>
 *   <li>Identity:    {@code effect.map(x -> x)} ≡ {@code effect}
 *   <li>Composition: {@code effect.map(f.andThen(g))} ≡ {@code effect.map(f).map(g)}
 * </ol>
 */
class FunctorLawsTest {

    private static final Function<Integer, Integer> addOne   = x -> x + 1;
    private static final Function<Integer, Integer> timesTwo = x -> x * 2;

    // -----------------------------------------------------------------------
    // F1: Identity — map(id) ≡ effect
    // -----------------------------------------------------------------------

    @Test
    void identity_succeedingEffect() {
        assertEquivalent(
                "functor-identity",
                Effect.succeed(42).map(x -> x),
                Effect.succeed(42));
    }

    @Test
    void identity_failingEffect() {
        assertEquivalent(
                "functor-identity",
                Effect.<IOException, Integer>fail(new IOException("err")).map(x -> x),
                Effect.fail(new IOException("err")));
    }

    @Test
    void identity_suspendEffect() {
        assertEquivalent(
                "functor-identity",
                Effect.<Throwable, Integer>suspend(() -> 42).map(x -> x),
                Effect.suspend(() -> 42));
    }

    // -----------------------------------------------------------------------
    // F2: Composition — map(f.andThen(g)) ≡ map(f).map(g)
    // -----------------------------------------------------------------------

    @Test
    void composition_succeedingEffect() {
        assertEquivalent(
                "functor-composition",
                Effect.succeed(3).map(addOne.andThen(timesTwo)),
                Effect.succeed(3).map(addOne).map(timesTwo));
    }

    @Test
    void composition_failingEffect() {
        assertEquivalent(
                "functor-composition",
                Effect.<IOException, Integer>fail(new IOException("err"))
                        .map(addOne.andThen(timesTwo)),
                Effect.<IOException, Integer>fail(new IOException("err"))
                        .map(addOne).map(timesTwo));
    }

    @Test
    void composition_suspendEffect() {
        assertEquivalent(
                "functor-composition",
                Effect.<Throwable, Integer>suspend(() -> 5).map(addOne.andThen(timesTwo)),
                Effect.<Throwable, Integer>suspend(() -> 5).map(addOne).map(timesTwo));
    }
}
