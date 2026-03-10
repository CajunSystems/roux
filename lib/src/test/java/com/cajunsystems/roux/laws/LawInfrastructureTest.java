package com.cajunsystems.roux.laws;

import com.cajunsystems.roux.Effect;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static com.cajunsystems.roux.laws.EffectLawSupport.assertEquivalent;
import static org.junit.jupiter.api.Assertions.*;

class LawInfrastructureTest {

    // -- Positive cases: equivalent effects pass --

    @Test
    void succeedingEffectsWithSameValue_areEquivalent() {
        assertEquivalent("smoke",
                Effect.succeed(42),
                Effect.succeed(42));
    }

    @Test
    void failingEffectsWithSameError_areEquivalent() {
        assertEquivalent("smoke",
                Effect.<IOException, Integer>fail(new IOException("boom")),
                Effect.<IOException, Integer>fail(new IOException("boom")));
    }

    @Test
    void chainedEffectsProducingSameResult_areEquivalent() {
        assertEquivalent("smoke",
                Effect.succeed(2).map(x -> x * 21),
                Effect.succeed(42));
    }

    // -- Negative cases: non-equivalent effects must fail the assertion --

    @Test
    void effectsWithDifferentValues_areNotEquivalent() {
        AssertionError err = assertThrows(AssertionError.class, () ->
                assertEquivalent("smoke",
                        Effect.succeed(1),
                        Effect.succeed(2))
        );
        assertTrue(err.getMessage().contains("smoke"),
                "Error message should reference the law name");
    }

    @Test
    void successVsFailure_areNotEquivalent() {
        assertThrows(AssertionError.class, () ->
                assertEquivalent("smoke",
                        Effect.succeed(42),
                        Effect.<IOException, Integer>fail(new IOException("error")))
        );
    }

    @Test
    void errorsWithDifferentMessages_areNotEquivalent() {
        assertThrows(AssertionError.class, () ->
                assertEquivalent("smoke",
                        Effect.<IOException, Integer>fail(new IOException("error-a")),
                        Effect.<IOException, Integer>fail(new IOException("error-b")))
        );
    }
}
