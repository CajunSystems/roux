package com.cajunsystems.roux.capability;

import java.util.HashMap;
import java.util.Map;

/**
 * Abstract base class for handlers that delegate to per-type registered handlers.
 *
 * <p>Subclasses call {@link #register} in their constructors (or lazily) to
 * associate concrete capability types with their handler logic.
 *
 * <p>Prefer the fluent {@link CapabilityHandler#builder()} when you don't need
 * to sub-class.
 */
public abstract class CompositeCapabilityHandler implements CapabilityHandler<Capability<?>> {

    private final Map<Class<?>, CapabilityHandler<?>> handlers = new HashMap<>();

    protected <C extends Capability<?>> void register(
            Class<C> capabilityType,
            CapabilityHandler<C> handler
    ) {
        handlers.put(capabilityType, handler);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <R> R handle(Capability<?> capability) throws Exception {
        Class<?> type = capability.getClass();

        // 1. Exact match on the concrete class
        CapabilityHandler<?> h = handlers.get(type);
        if (h != null) {
            return ((CapabilityHandler<Capability<?>>) h).handle(capability);
        }

        // 2. Walk the interface hierarchy (BFS to find the most specific registered interface)
        for (Class<?> iface : collectInterfaces(type)) {
            h = handlers.get(iface);
            if (h != null) {
                return ((CapabilityHandler<Capability<?>>) h).handle(capability);
            }
        }

        throw new UnsupportedOperationException(
                "No handler registered for: " + type.getName()
        );
    }

    /**
     * Collect all interfaces implemented by {@code clazz} (and its superclasses)
     * in breadth-first order so that more specific interfaces are tried first.
     */
    private static java.util.List<Class<?>> collectInterfaces(Class<?> clazz) {
        java.util.List<Class<?>> result = new java.util.ArrayList<>();
        java.util.Deque<Class<?>> queue = new java.util.ArrayDeque<>();

        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Class<?> iface : current.getInterfaces()) {
                if (!result.contains(iface)) {
                    queue.add(iface);
                }
            }
            current = current.getSuperclass();
        }

        while (!queue.isEmpty()) {
            Class<?> iface = queue.poll();
            if (!result.contains(iface)) {
                result.add(iface);
                for (Class<?> superIface : iface.getInterfaces()) {
                    if (!result.contains(superIface)) {
                        queue.add(superIface);
                    }
                }
            }
        }

        return result;
    }
}
