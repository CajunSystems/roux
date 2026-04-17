package com.cajunsystems.roux.laws;

import com.cajunsystems.roux.Effect;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.function.Function;

import static com.cajunsystems.roux.laws.EffectLawSupport.assertEquivalent;

/**
 * Verifies error channel laws for Effect&lt;E,A&gt;.
 *
 * <p>Laws verified:
 * <ol>
 *   <li>catchAll identity:    {@code effect.catchAll(Effect::fail)} ≡ {@code effect}
 *   <li>mapError identity:    {@code effect.mapError(e -> e)} ≡ {@code effect}
 *   <li>mapError composition: {@code effect.mapError(f).mapError(g)} ≡ {@code effect.mapError(f.andThen(g))}
 *   <li>attempt round-trip:   {@code effect.attempt().flatMap(fold)} ≡ {@code effect}
 * </ol>
 */
class ErrorChannelLawsTest {

    // -----------------------------------------------------------------------
    // E1: catchAll identity — effect.catchAll(Effect::fail) ≡ effect
    // -----------------------------------------------------------------------

    @Test
    void catchAllIdentity_succeedingEffect() {
        Effect<Throwable, Integer> effect = Effect.succeed(42);
        assertEquivalent(
                "catchAll-identity",
                effect.catchAll(Effect::fail),
                effect);
    }

    @Test
    void catchAllIdentity_failingEffect() {
        Effect<Throwable, Integer> effect = Effect.fail(new IOException("err"));
        assertEquivalent(
                "catchAll-identity",
                effect.catchAll(Effect::fail),
                effect);
    }

    // -----------------------------------------------------------------------
    // E2: mapError identity — effect.mapError(e -> e) ≡ effect
    // -----------------------------------------------------------------------

    @Test
    void mapErrorIdentity_succeedingEffect() {
        Effect<Throwable, Integer> effect = Effect.succeed(42);
        assertEquivalent(
                "mapError-identity",
                effect.mapError(e -> e),
                effect);
    }

    @Test
    void mapErrorIdentity_failingEffect() {
        Effect<Throwable, Integer> effect = Effect.fail(new IOException("err"));
        assertEquivalent(
                "mapError-identity",
                effect.mapError(e -> e),
                effect);
    }

    // -----------------------------------------------------------------------
    // E3: mapError composition — mapError(f).mapError(g) ≡ mapError(f.andThen(g))
    // -----------------------------------------------------------------------

    @Test
    void mapErrorComposition_succeedingEffect() {
        Function<Throwable, RuntimeException> wrapMsg =
                e -> new RuntimeException("wrapped: " + e.getMessage());
        Function<RuntimeException, IllegalStateException> wrapAgain =
                e -> new IllegalStateException(e.getMessage());

        Effect<Throwable, Integer> effect = Effect.succeed(42);
        assertEquivalent(
                "mapError-composition",
                effect.mapError(wrapMsg).mapError(wrapAgain),
                effect.mapError(wrapMsg.andThen(wrapAgain)));
    }

    @Test
    void mapErrorComposition_failingEffect() {
        Function<Throwable, RuntimeException> wrapMsg =
                e -> new RuntimeException("wrapped: " + e.getMessage());
        Function<RuntimeException, IllegalStateException> wrapAgain =
                e -> new IllegalStateException(e.getMessage());

        Effect<Throwable, Integer> effect = Effect.fail(new IOException("original"));
        assertEquivalent(
                "mapError-composition",
                effect.mapError(wrapMsg).mapError(wrapAgain),
                effect.mapError(wrapMsg.andThen(wrapAgain)));
    }

    // -----------------------------------------------------------------------
    // E4: attempt round-trip — attempt().flatMap(fold) ≡ effect
    // -----------------------------------------------------------------------

    @Test
    void attemptRoundTrip_succeedingEffect() {
        Effect<Throwable, Integer> effect = Effect.succeed(42);
        assertEquivalent(
                "attempt-round-trip",
                effect.attempt().flatMap(either -> either.fold(
                        e -> Effect.fail(e),
                        a -> Effect.succeed(a))),
                effect);
    }

    @Test
    void attemptRoundTrip_failingEffect() {
        Effect<Throwable, Integer> effect = Effect.fail(new IOException("err"));
        assertEquivalent(
                "attempt-round-trip",
                effect.attempt().flatMap(either -> either.fold(
                        e -> Effect.fail(e),
                        a -> Effect.succeed(a))),
                effect);
    }
}
