package com.cajunsystems.roux.data;

@FunctionalInterface
public interface ThrowingRunnable {
    void run() throws Exception;
}
