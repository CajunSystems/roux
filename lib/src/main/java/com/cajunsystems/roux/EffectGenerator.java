package com.cajunsystems.roux;

@FunctionalInterface
public interface EffectGenerator<E extends Throwable, R> {
    R generate(GeneratorContext<E> ctx) throws E;
}
