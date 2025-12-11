package com.cajunsystems.roux;

import com.cajunsystems.roux.capability.Capability;
import com.cajunsystems.roux.capability.CapabilityHandler;
import com.cajunsystems.roux.data.ThrowingSupplier;

public interface GeneratorContext<E extends Throwable> {
    <R> R perform(Capability<R> capability) throws E;
    
    default <R> Effect<E, R> lift(Capability<R> capability) {
        return Effect.from(capability);
    }
    
    <R> R call(ThrowingSupplier<R> operation) throws E;
    
    <E2 extends E, R> R yield(Effect<E2, R> effect) throws E;
    
    CapabilityHandler<Capability<?>> handler();
}
