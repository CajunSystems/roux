package com.cajunsystems.roux;

import com.cajunsystems.roux.capability.Capability;
import com.cajunsystems.roux.capability.CapabilityHandler;

import java.util.concurrent.CompletableFuture;
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

    /**
     * Run an effect asynchronously and return a {@link CompletableFuture} that
     * completes with the result. Useful for interoperating with APIs that expect
     * {@code CompletableFuture}, such as Spring WebFlux or Jakarta EE async endpoints.
     *
     * <pre>{@code
     * CompletableFuture<User> future = runtime.toFuture(fetchUser(id));
     * }</pre>
     */
    default <E extends Throwable, A> CompletableFuture<A> toFuture(Effect<E, A> effect) {
        CompletableFuture<A> future = new CompletableFuture<>();
        runAsync(
                effect.widen(),
                future::complete,
                future::completeExceptionally
        );
        return future;
    }
}