package com.cajunsystems.roux.data;

@FunctionalInterface
public interface ThrowingFunction<A, B> {
    B apply(A input) throws Exception;
}