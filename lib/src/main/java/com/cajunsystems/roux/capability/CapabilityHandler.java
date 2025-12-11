package com.cajunsystems.roux.capability;

import java.util.HashMap;
import java.util.Map;

@FunctionalInterface
public interface CapabilityHandler<C extends Capability<?>> {
    <R> R handle(C capability) throws Exception;
    
    @SuppressWarnings("unchecked")
    default CapabilityHandler<Capability<?>> widen() {
        return (CapabilityHandler<Capability<?>>) this;
    }
    
    default CapabilityHandler<Capability<?>> orElse(CapabilityHandler<?> fallback) {
        return new CapabilityHandler<Capability<?>>() {
            @Override
            @SuppressWarnings("unchecked")
            public <R> R handle(Capability<?> capability) throws Exception {
                try {
                    return CapabilityHandler.this.handle((C) capability);
                } catch (ClassCastException e) {
                    return ((CapabilityHandler<Capability<?>>) fallback).handle(capability);
                }
            }
        };
    }
    
    @SafeVarargs
    static CapabilityHandler<Capability<?>> compose(CapabilityHandler<?>... handlers) {
        return new CapabilityHandler<Capability<?>>() {
            @Override
            @SuppressWarnings("unchecked")
            public <R> R handle(Capability<?> capability) throws Exception {
                for (CapabilityHandler<?> handler : handlers) {
                    try {
                        return ((CapabilityHandler<Capability<?>>) handler).handle(capability);
                    } catch (ClassCastException e) {
                    }
                }
                throw new UnsupportedOperationException(
                    "No handler found for capability: " + capability.getClass()
                );
            }
        };
    }
}
