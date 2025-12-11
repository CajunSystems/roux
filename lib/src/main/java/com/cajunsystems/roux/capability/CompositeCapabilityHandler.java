package com.cajunsystems.roux.capability;

import java.util.HashMap;
import java.util.Map;

public abstract class CompositeCapabilityHandler implements CapabilityHandler<Capability<?>> {
    private final Map<Class<?>, CapabilityHandler<?>> handlers = new HashMap<>();
    
    protected <C extends Capability<?>> void register(
            Class<C> capabilityType, 
            CapabilityHandler<C> handler) {
        handlers.put(capabilityType, handler);
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public <R> R handle(Capability<?> capability) throws Exception {
        Class<?> capabilityInterface = findCapabilityInterface(capability.getClass());
        CapabilityHandler handler = handlers.get(capabilityInterface);
        if (handler == null) {
            throw new UnsupportedOperationException(
                "No handler registered for: " + capability.getClass()
            );
        }
        return (R) handler.handle(capability);
    }
    
    private Class<?> findCapabilityInterface(Class<?> clazz) {
        for (Class<?> iface : clazz.getInterfaces()) {
            if (Capability.class.isAssignableFrom(iface) && iface != Capability.class) {
                return iface;
            }
        }
        if (clazz.getSuperclass() != null) {
            return findCapabilityInterface(clazz.getSuperclass());
        }
        return clazz;
    }
}
