package com.cajunsystems.roux;

import com.cajunsystems.roux.capability.Capability;
import com.cajunsystems.roux.capability.CapabilityHandler;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

public interface EffectRuntime {
    <E extends Throwable, A> A unsafeRun(Effect<E, A> effect) throws E;

    <E extends Throwable, A> A unsafeRunWithHandler(
            Effect<E, A> effect,
            CapabilityHandler<Capability<?>> handler
    ) throws E;

    <E extends Throwable, A> CancellationHandle runAsync(
            Effect<E, A> effect,
            Consumer<A> onSuccess,
            Consumer<Throwable> onError
    );

    <E extends Throwable, A> Fiber<E, A> executeFork(Effect.Fork<E, A> fork);

    Executor executor();
}