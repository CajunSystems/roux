package com.cajunsystems.roux;

import com.cajunsystems.roux.data.Unit;

import java.util.UUID;

public interface Fiber<E extends Throwable, A> {
    Effect<E, A> join();
    Effect<Throwable, Unit> interrupt();
    UUID id();
}