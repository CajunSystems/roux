package com.cajunsystems.roux;

import java.time.Duration;

public interface CancellationHandle {
    void cancel();
    boolean isCancelled();
    void await() throws InterruptedException;
    boolean await(Duration timeout) throws InterruptedException;
}