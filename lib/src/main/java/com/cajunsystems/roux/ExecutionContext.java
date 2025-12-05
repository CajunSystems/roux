package com.cajunsystems.roux;

import java.util.concurrent.atomic.AtomicBoolean;

class ExecutionContext {
    private static final ExecutionContext ROOT = new ExecutionContext(null);

    private final ExecutionContext parent;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    private ExecutionContext(ExecutionContext parent) {
        this.parent = parent;
    }

    static ExecutionContext root() {
        return ROOT;
    }

    boolean isCancelled() {
        return cancelled.get() || (parent != null && parent.isCancelled());
    }

    void cancel() {
        cancelled.set(true);
    }
}