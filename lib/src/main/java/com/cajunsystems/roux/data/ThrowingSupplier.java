package com.cajunsystems.roux.data;

@FunctionalInterface
public interface ThrowingSupplier<A> {
    A get() throws Exception;
}