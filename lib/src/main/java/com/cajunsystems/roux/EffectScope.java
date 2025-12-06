package com.cajunsystems.roux;


import com.cajunsystems.roux.data.Unit;

public interface EffectScope {
    /**
     * Fork an effect within this scope. The forked effect will be
     * automatically cancelled if the scope exits before it completes.
     */
    <E extends Throwable, A> Effect<Throwable, Fiber<E, A>> fork(Effect<E, A> effect);

    /**
     * Cancel all effects forked within this scope.
     */
    Effect<Throwable, Unit> cancelAll();

    /**
     * Check if this scope has been cancelled.
     */
    boolean isCancelled();
}