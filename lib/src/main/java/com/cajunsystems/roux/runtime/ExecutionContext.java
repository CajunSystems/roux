package com.cajunsystems.roux.runtime;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

class ExecutionContext {
    private static final ExecutionContext ROOT = new ExecutionContext(null);

    private final ExecutionContext parent;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final List<ExecutionContext> children = new CopyOnWriteArrayList<>();

    private ExecutionContext(ExecutionContext parent) {
        this.parent = parent;
    }

    static ExecutionContext root() {
        return ROOT;
    }

    ExecutionContext childContext() {
        ExecutionContext child = new ExecutionContext(this);
        children.add(child);
        return child;
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