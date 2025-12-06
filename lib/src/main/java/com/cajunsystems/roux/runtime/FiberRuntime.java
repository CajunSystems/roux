package com.cajunsystems.roux.runtime;

import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.Fiber;
import com.cajunsystems.roux.data.Either;
import com.cajunsystems.roux.data.Unit;
import com.cajunsystems.roux.exception.CancelledException;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

class FiberRuntime<E extends Throwable, A> implements Fiber<E, A> {
    private final UUID id;
    private final CompletableFuture<Either<Throwable, A>> resultFuture;
    private final Thread executionThread;
    private final AtomicBoolean interrupted;

    FiberRuntime(
            UUID id,
            CompletableFuture<Either<Throwable, A>> resultFuture,
            Thread executionThread,
            AtomicBoolean interrupted
    ) {
        this.id = id;
        this.resultFuture = resultFuture;
        this.executionThread = executionThread;
        this.interrupted = interrupted;
    }

    @Override
    public Effect<E, A> join() {
        return Effect.suspend(() -> {
            try {
                Either<Throwable, A> result = resultFuture.get();
                return switch (result) {
                    case Either.Left<Throwable, A> left -> {
                        Throwable error = left.value();
                        if (error instanceof RuntimeException re) {
                            throw re;
                        }
                        if (error instanceof Error err) {
                            throw err;
                        }
                        throw new RuntimeException(error);
                    }
                    case Either.Right<Throwable, A> right -> right.value();
                };
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancelledException(e);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
    @Override
    public Effect<Throwable, Unit> interrupt() {
        return Effect.suspend(() -> {
            if (interrupted.compareAndSet(false, true)) {
                executionThread.interrupt();
            }
            return Unit.unit();
        });
    }

    @Override
    public UUID id() {
        return id;
    }
}