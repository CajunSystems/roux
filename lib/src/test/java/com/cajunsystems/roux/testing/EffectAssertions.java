package com.cajunsystems.roux.testing;

import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.capability.Capability;
import com.cajunsystems.roux.capability.CapabilityHandler;
import com.cajunsystems.roux.runtime.DefaultEffectRuntime;

/**
 * Entry point for fluent Effect assertions.
 *
 * <p>Usage:
 * <pre>{@code
 * EffectAssertions.assertThat(Effect.succeed(42))
 *     .succeeds()
 *     .succeedsWith(42)
 *     .succeedsWith(v -> v > 0);
 *
 * EffectAssertions.assertThat(Effect.fail(new IOException("boom")))
 *     .fails()
 *     .failsWith(IOException.class)
 *     .failsWith(e -> e.getMessage().contains("boom"));
 *
 * // With capability handler:
 * EffectAssertions.assertThat(capabilityEffect, handler)
 *     .succeedsWith("expected");
 *
 * // Extract value for custom assertions:
 * String result = EffectAssertions.assertThat(effect).andReturn();
 * }</pre>
 *
 * <p>The effect is executed once when {@code assertThat} is called.
 * All chained assertions on the returned {@link EffectAssert} operate on
 * the stored outcome — there is no re-execution.
 */
public final class EffectAssertions {

    private EffectAssertions() {}

    /**
     * Execute {@code effect} using a fresh {@link DefaultEffectRuntime} and return
     * a fluent {@link EffectAssert} for making assertions about the outcome.
     */
    public static <E extends Throwable, A> EffectAssert<E, A> assertThat(
            Effect<E, A> effect
    ) {
        try (DefaultEffectRuntime runtime = DefaultEffectRuntime.create()) {
            return new EffectAssert<>(runCapturing(runtime, effect));
        }
    }

    /**
     * Execute {@code effect} with the given capability handler and return a fluent
     * {@link EffectAssert} for making assertions about the outcome.
     */
    public static <E extends Throwable, A> EffectAssert<E, A> assertThat(
            Effect<E, A> effect,
            CapabilityHandler<Capability<?>> handler
    ) {
        try (DefaultEffectRuntime runtime = DefaultEffectRuntime.create()) {
            return new EffectAssert<>(runCapturingWithHandler(runtime, effect, handler));
        }
    }

    // -- Internals --

    private static <E extends Throwable, A> EffectAssert.Outcome<A> runCapturing(
            DefaultEffectRuntime runtime,
            Effect<E, A> effect
    ) {
        try {
            return new EffectAssert.Outcome.Success<>(runtime.unsafeRun(effect));
        } catch (Throwable e) {
            return new EffectAssert.Outcome.Failure<>(e);
        }
    }

    private static <E extends Throwable, A> EffectAssert.Outcome<A> runCapturingWithHandler(
            DefaultEffectRuntime runtime,
            Effect<E, A> effect,
            CapabilityHandler<Capability<?>> handler
    ) {
        try {
            return new EffectAssert.Outcome.Success<>(runtime.unsafeRunWithHandler(effect, handler));
        } catch (Throwable e) {
            return new EffectAssert.Outcome.Failure<>(e);
        }
    }
}
