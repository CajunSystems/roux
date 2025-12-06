package com.cajunsystems.roux.runtime;

import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.EffectRuntime;
import com.cajunsystems.roux.EffectScope;
import com.cajunsystems.roux.Fiber;
import com.cajunsystems.roux.data.Unit;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicBoolean;

class EffectScopeRuntime implements EffectScope {
    private final StructuredTaskScope<Object> taskScope;
    private final EffectRuntime runtime;
    private final List<Fiber<?, ?>> forkedFibers = new CopyOnWriteArrayList<>();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    EffectScopeRuntime(StructuredTaskScope<Object> taskScope, EffectRuntime runtime) {
        this.taskScope = taskScope;
        this.runtime = runtime;
    }

    @Override
    public <E extends Throwable, A> Effect<Throwable, Fiber<E, A>> fork(Effect<E, A> effect) {
        return Effect.suspend(() -> {
            // Create Fork effect and execute it
            Effect.Fork<E, A> forkEffect = new Effect.Fork<>(effect);
            Fiber<E, A> fiber = runtime.executeFork(forkEffect);

            // Track the fiber in this scope
            forkedFibers.add(fiber);

            return fiber;
        });
    }

    @Override
    public Effect<Throwable, Unit> cancelAll() {
        return Effect.suspend(() -> {
            if (cancelled.compareAndSet(false, true)) {
                // Interrupt all forked fibers
                for (Fiber<?, ?> fiber : forkedFibers) {
                    try {
                        runtime.unsafeRun(fiber.interrupt());
                    } catch (Throwable t) {
                        // Ignore errors during cancellation
                        // (fiber might already be done or failed)
                    }
                }

                // Shutdown the task scope
                taskScope.shutdown();
            }
            return Unit.unit();
        });
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

    List<Fiber<?, ?>> getFibers() {
        return List.copyOf(forkedFibers);
    }
}