package com.cajunsystems.roux.laws;

import com.cajunsystems.roux.Effect;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.function.Function;

import static com.cajunsystems.roux.laws.EffectLawSupport.assertEquivalent;

/**
 * Verifies monad laws for Effect&lt;E,A&gt;.
 *
 * <p>A monad must satisfy:
 * <ol>
 *   <li>Left identity:  {@code Effect.succeed(a).flatMap(f)} ≡ {@code f.apply(a)}
 *   <li>Right identity: {@code effect.flatMap(Effect::succeed)} ≡ {@code effect}
 *   <li>Associativity:  {@code effect.flatMap(f).flatMap(g)}
 *                       ≡ {@code effect.flatMap(a -> f.apply(a).flatMap(g))}
 * </ol>
 */
class MonadLawsTest {

    private static final Function<Integer, Effect<Throwable, Integer>> addOne =
            a -> Effect.succeed(a + 1);
    private static final Function<Integer, Effect<Throwable, Integer>> timesTwo =
            b -> Effect.succeed(b * 2);

    // -----------------------------------------------------------------------
    // M1: Left identity — succeed(a).flatMap(f) ≡ f(a)
    // -----------------------------------------------------------------------

    @Test
    void leftIdentity_withValue() {
        int a = 42;
        assertEquivalent(
                "monad-left-identity",
                Effect.<Throwable, Integer>succeed(a).flatMap(addOne),
                addOne.apply(a));
    }

    @Test
    void leftIdentity_withDifferentValue() {
        int a = 0;
        assertEquivalent(
                "monad-left-identity",
                Effect.<Throwable, Integer>succeed(a).flatMap(timesTwo),
                timesTwo.apply(a));
    }

    // -----------------------------------------------------------------------
    // M2: Right identity — effect.flatMap(succeed) ≡ effect
    // -----------------------------------------------------------------------

    @Test
    void rightIdentity_succeedingEffect() {
        assertEquivalent(
                "monad-right-identity",
                Effect.<Throwable, Integer>succeed(42).flatMap(Effect::succeed),
                Effect.succeed(42));
    }

    @Test
    void rightIdentity_failingEffect() {
        // Use Throwable error type directly to avoid widen() ceremony
        Effect<Throwable, Integer> base = Effect.fail(new IOException("err"));
        assertEquivalent(
                "monad-right-identity",
                base.flatMap(Effect::succeed),
                base);
    }

    @Test
    void rightIdentity_suspendEffect() {
        assertEquivalent(
                "monad-right-identity",
                Effect.<Throwable, Integer>suspend(() -> 7).flatMap(Effect::succeed),
                Effect.suspend(() -> 7));
    }

    // -----------------------------------------------------------------------
    // M3: Associativity — (effect.flatMap(f)).flatMap(g) ≡ effect.flatMap(a -> f(a).flatMap(g))
    // -----------------------------------------------------------------------

    @Test
    void associativity_succeedingEffect() {
        assertEquivalent(
                "monad-associativity",
                Effect.<Throwable, Integer>succeed(3).flatMap(addOne).flatMap(timesTwo),
                Effect.<Throwable, Integer>succeed(3).flatMap(a -> addOne.apply(a).flatMap(timesTwo)));
    }

    @Test
    void associativity_failingEffect() {
        assertEquivalent(
                "monad-associativity",
                Effect.<Throwable, Integer>fail(new RuntimeException("fail"))
                        .flatMap(addOne).flatMap(timesTwo),
                Effect.<Throwable, Integer>fail(new RuntimeException("fail"))
                        .flatMap(a -> addOne.apply(a).flatMap(timesTwo)));
    }

    @Test
    void associativity_suspendEffect() {
        assertEquivalent(
                "monad-associativity",
                Effect.<Throwable, Integer>suspend(() -> 10).flatMap(addOne).flatMap(timesTwo),
                Effect.<Throwable, Integer>suspend(() -> 10).flatMap(a -> addOne.apply(a).flatMap(timesTwo)));
    }
}
