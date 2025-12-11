package com.cajunsystems.roux.runtime;

import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.EffectRuntime;
import com.cajunsystems.roux.GeneratorContext;
import com.cajunsystems.roux.capability.Capability;
import com.cajunsystems.roux.capability.CapabilityHandler;
import com.cajunsystems.roux.data.ThrowingSupplier;

class GeneratorContextImpl<E extends Throwable> implements GeneratorContext<E> {
    private final CapabilityHandler<Capability<?>> handler;
    private final EffectRuntime runtime;
    
    GeneratorContextImpl(
            CapabilityHandler<Capability<?>> handler,
            EffectRuntime runtime
    ) {
        this.handler = handler;
        this.runtime = runtime;
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public <R> R perform(Capability<R> capability) throws E {
        try {
            return handler.handle(capability);
        } catch (Exception e) {
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw (E) e;
        }
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public <R> R call(ThrowingSupplier<R> operation) throws E {
        try {
            return operation.get();
        } catch (Exception e) {
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw (E) e;
        }
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public <E2 extends E, R> R yield(Effect<E2, R> effect) throws E {
        try {
            return runtime.unsafeRunWithHandler(effect, handler);
        } catch (Throwable e) {
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw (E) e;
        }
    }
    
    @Override
    public CapabilityHandler<Capability<?>> handler() {
        return handler;
    }
}
