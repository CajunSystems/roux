package com.cajunsystems.roux.testing;

import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.capability.Capability;
import com.cajunsystems.roux.capability.CapabilityHandler;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static com.cajunsystems.roux.testing.EffectAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class EffectAssertionsTest {

    // -----------------------------------------------------------------------
    // succeeds()
    // -----------------------------------------------------------------------

    @Test
    void succeeds_passesForSucceedingEffect() {
        assertThat(Effect.succeed(42)).succeeds();
    }

    @Test
    void succeeds_failsForFailingEffect() {
        assertThrows(AssertionError.class, () ->
                assertThat(Effect.<IOException, Integer>fail(new IOException("boom"))).succeeds());
    }

    // -----------------------------------------------------------------------
    // succeedsWith(value)
    // -----------------------------------------------------------------------

    @Test
    void succeedsWith_value_passesForMatchingValue() {
        assertThat(Effect.succeed(42)).succeedsWith(42);
    }

    @Test
    void succeedsWith_value_failsForWrongValue() {
        assertThrows(AssertionError.class, () ->
                assertThat(Effect.succeed(42)).succeedsWith(99));
    }

    @Test
    void succeedsWith_value_failsForFailingEffect() {
        assertThrows(AssertionError.class, () ->
                assertThat(Effect.<IOException, Integer>fail(new IOException("err")))
                        .succeedsWith(42));
    }

    // -----------------------------------------------------------------------
    // succeedsWith(predicate)
    // -----------------------------------------------------------------------

    @Test
    void succeedsWith_predicate_passesWhenPredicateHolds() {
        assertThat(Effect.succeed(42)).succeedsWith(v -> v > 0);
    }

    @Test
    void succeedsWith_predicate_failsWhenPredicateDoesNotHold() {
        assertThrows(AssertionError.class, () ->
                assertThat(Effect.succeed(42)).succeedsWith(v -> v > 100));
    }

    // -----------------------------------------------------------------------
    // fails()
    // -----------------------------------------------------------------------

    @Test
    void fails_passesForFailingEffect() {
        assertThat(Effect.<IOException, Integer>fail(new IOException("err"))).fails();
    }

    @Test
    void fails_failsForSucceedingEffect() {
        assertThrows(AssertionError.class, () ->
                assertThat(Effect.succeed(42)).fails());
    }

    // -----------------------------------------------------------------------
    // failsWith(Class)
    // -----------------------------------------------------------------------

    @Test
    void failsWith_class_passesForCorrectExceptionType() {
        assertThat(Effect.<IOException, Integer>fail(new IOException("err")))
                .failsWith(IOException.class);
    }

    @Test
    void failsWith_class_passesForSupertype() {
        assertThat(Effect.<IOException, Integer>fail(new IOException("err")))
                .failsWith(Exception.class);
    }

    @Test
    void failsWith_class_failsForWrongExceptionType() {
        assertThrows(AssertionError.class, () ->
                assertThat(Effect.<IOException, Integer>fail(new IOException("err")))
                        .failsWith(RuntimeException.class));
    }

    // -----------------------------------------------------------------------
    // failsWith(predicate)
    // -----------------------------------------------------------------------

    @Test
    void failsWith_predicate_passesWhenPredicateHolds() {
        assertThat(Effect.<IOException, Integer>fail(new IOException("boom")))
                .failsWith(e -> e.getMessage().contains("boom"));
    }

    @Test
    void failsWith_predicate_failsWhenPredicateDoesNotHold() {
        assertThrows(AssertionError.class, () ->
                assertThat(Effect.<IOException, Integer>fail(new IOException("boom")))
                        .failsWith(e -> e.getMessage().contains("xyz")));
    }

    // -----------------------------------------------------------------------
    // Chaining
    // -----------------------------------------------------------------------

    @Test
    void chainedSuccessAssertions_allPass() {
        assertThat(Effect.succeed(42))
                .succeeds()
                .succeedsWith(42)
                .succeedsWith(v -> v > 0 && v < 100);
    }

    @Test
    void chainedFailureAssertions_allPass() {
        assertThat(Effect.<IOException, String>fail(new IOException("network error")))
                .fails()
                .failsWith(IOException.class)
                .failsWith(e -> e.getMessage().startsWith("network"));
    }

    // -----------------------------------------------------------------------
    // andReturn() / andError()
    // -----------------------------------------------------------------------

    @Test
    void andReturn_returnsValueForCustomAssertions() {
        Integer value = assertThat(Effect.succeed(42)).andReturn();
        assertEquals(42, value);
    }

    @Test
    void andReturn_throwsAssertionErrorForFailingEffect() {
        assertThrows(AssertionError.class, () ->
                assertThat(Effect.<IOException, Integer>fail(new IOException("err"))).andReturn());
    }

    @Test
    void andError_returnsErrorForCustomAssertions() {
        IOException err = assertThat(Effect.<IOException, String>fail(new IOException("details"))).andError();
        assertEquals("details", err.getMessage());
    }

    @Test
    void andError_throwsAssertionErrorForSucceedingEffect() {
        assertThrows(AssertionError.class, () ->
                assertThat(Effect.succeed(42)).andError());
    }

    // -----------------------------------------------------------------------
    // Handler overload
    // -----------------------------------------------------------------------

    @Test
    void assertThatWithHandler_executesCapabilityEffect() {
        sealed interface Greet<R> extends Capability<R> {
            record Hello(String name) implements Greet<String> {}
        }
        CapabilityHandler<Capability<?>> handler = CapabilityHandler.builder()
                .on(Greet.Hello.class, c -> "Hello, " + c.name())
                .build();
        Effect<Throwable, String> effect = new Greet.Hello("World").toEffect();

        assertThat(effect, handler).succeedsWith("Hello, World");
    }
}
