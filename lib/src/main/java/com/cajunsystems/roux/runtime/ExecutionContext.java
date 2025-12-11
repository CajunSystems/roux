package com.cajunsystems.roux.runtime;

import com.cajunsystems.roux.capability.Capability;
import com.cajunsystems.roux.capability.CapabilityHandler;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

class ExecutionContext {
    private static final ExecutionContext ROOT = new ExecutionContext(null, null);

    private final ExecutionContext parent;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final List<ExecutionContext> children = new CopyOnWriteArrayList<>();
    private final CapabilityHandler<Capability<?>> capabilityHandler;

    private ExecutionContext(ExecutionContext parent, CapabilityHandler<Capability<?>> capabilityHandler) {
        this.parent = parent;
        this.capabilityHandler = capabilityHandler;
    }

    static ExecutionContext root() {
        return ROOT;
    }

    ExecutionContext childContext() {
        ExecutionContext child = new ExecutionContext(this, this.capabilityHandler);
        children.add(child);
        return child;
    }

    ExecutionContext withCapabilityHandler(CapabilityHandler<Capability<?>> handler) {
        return new ExecutionContext(this.parent, handler);
    }

    CapabilityHandler<Capability<?>> getCapabilityHandler() {
        if (capabilityHandler != null) {
            return capabilityHandler;
        }
        if (parent != null) {
            return parent.getCapabilityHandler();
        }
        throw new IllegalStateException("No capability handler found in execution context");
    }

    boolean isCancelled() {
        return cancelled.get() || (parent != null && parent.isCancelled());
    }

    void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            children.forEach(ExecutionContext::cancel);
        }
    }
}