package com.cajunsystems.roux.runtime;

import com.cajunsystems.roux.CancellationHandle;
import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.EffectRuntime;
import com.cajunsystems.roux.Fiber;
import com.cajunsystems.roux.data.Either;
import com.cajunsystems.roux.exception.CancelledException;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class DefaultEffectRuntime implements EffectRuntime {
    private final ExecutorService executor;

    public DefaultEffectRuntime(ExecutorService executor) {
        this.executor = executor;
    }

    public static DefaultEffectRuntime create() {
        return new DefaultEffectRuntime(
                Executors.newVirtualThreadPerTaskExecutor()
        );
    }

    @Override
    public <E extends Throwable, A> A unsafeRun(Effect<E, A> effect) throws E {
        return execute(effect, ExecutionContext.root());
    }

    @Override
    public <E extends Throwable, A> CancellationHandle runAsync(
            Effect<E, A> effect,
            Consumer<A> onSuccess,
            Consumer<Throwable> onError
    ) {
        CompletableFuture<Void> completionFuture = new CompletableFuture<>();
        AtomicReference<Thread> threadRef = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean(false);

        executor.execute(() -> {
            threadRef.set(Thread.currentThread());

            try {
                A result = execute(effect, ExecutionContext.root());
                if (!cancelled.get()) {
                    onSuccess.accept(result);
                }
                completionFuture.complete(null);
            } catch (CancelledException e) {
                completionFuture.completeExceptionally(e);
            } catch (Throwable error) {
                if (!cancelled.get()) {
                    onError.accept(error);
                }
                completionFuture.completeExceptionally(error);
            }
        });

        while (threadRef.get() == null) {
            Thread.onSpinWait();
        }

        return new DefaultCancellationHandle(threadRef.get(), completionFuture, cancelled);
    }

    @Override
    public <E extends Throwable, A> Fiber<E, A> executeFork(Effect.Fork<E, A> fork) {
        CompletableFuture<Either<Throwable, A>> resultFuture = new CompletableFuture<>();
        AtomicReference<Thread> threadRef = new AtomicReference<>();
        UUID fiberId = UUID.randomUUID();
        AtomicBoolean interrupted = new AtomicBoolean(false);

        executor.execute(() -> {
            threadRef.set(Thread.currentThread());
            ExecutionContext childCtx = ExecutionContext.root().childContext();

            try {
                A result = execute(fork.effect(), childCtx);
                resultFuture.complete(Either.right(result));
            } catch (Throwable error) {
                resultFuture.complete(Either.left(error));
            }
        });

        while (threadRef.get() == null) {
            Thread.onSpinWait();
        }

        return new FiberRuntime<>(fiberId, resultFuture, threadRef.get(), interrupted);
    }

    @Override
    public Executor executor() {
        return executor;
    }

    private <E extends Throwable, A> A execute(
            Effect<E, A> effect,
            ExecutionContext ctx
    ) throws E {
        // Checkpoint at boundary
        if (Thread.interrupted()) {
            throw new CancelledException();
        }

        return switch (effect) {
            case Effect.Pure<E, A> pure -> pure.value();
            case Effect.Fail<E, A> fail -> throw fail.error();
            case Effect.Suspend<E, A> suspend -> {
                try {
                    yield suspend.thunk().get();
                } catch (Exception e) {
                    if (e instanceof RuntimeException re) {
                        throw re;
                    }
                    // Convert checked exception to RuntimeException
                    throw new RuntimeException(e);
                }
            }
            case Effect.FlatMap<E, ?, A> flatMap -> executeFlatMap(flatMap, ctx);
            case Effect.Fold<?, E, ?, A> fold -> executeFold(fold, ctx);
            case Effect.MapError<?, E, A> mapError -> executeMapError(mapError, ctx);
            case Effect.Fork<?, ?> fork -> (A) executeFork((Effect.Fork<?, ?>) fork);
            case Effect.Scoped<E, A> scoped -> executeScoped(scoped, ctx);
        };
    }

    @SuppressWarnings("unchecked")
    private <E extends Throwable, A, B> B executeFlatMap(
            Effect.FlatMap<E, A, B> flatMap,
            ExecutionContext ctx
    ) throws E {
        A intermediate = (A) execute((Effect<E, A>) flatMap.source(), ctx);
        Effect<E, B> next = flatMap.f().apply(intermediate);
        return execute(next, ctx);
    }

    @SuppressWarnings("unchecked")
    private <E extends Throwable, E2 extends Throwable, A, B> B executeFold(
            Effect.Fold<E, E2, A, B> fold,
            ExecutionContext ctx
    ) throws E2 {
        try {
            A result = (A) execute((Effect<E, A>) fold.source(), ctx);
            Effect<E2, B> next = fold.onSuccess().apply(result);
            return execute(next, ctx);
        } catch (CancelledException e) {
            throw e;
        } catch (Throwable error) {
            @SuppressWarnings("unchecked")
            E typedError = (E) error;
            Effect<E2, B> recovery = fold.onError().apply(typedError);
            return execute(recovery, ctx);
        }
    }

    @SuppressWarnings("unchecked")
    private <E extends Throwable, E2 extends Throwable, A> A executeMapError(
            Effect.MapError<E, E2, A> mapError,
            ExecutionContext ctx
    ) throws E2 {
        try {
            return (A) execute((Effect<E, A>) mapError.source(), ctx);
        } catch (CancelledException e) {
            throw e;
        } catch (Throwable error) {
            @SuppressWarnings("unchecked")
            E typedError = (E) error;
            throw mapError.f().apply(typedError);
        }
    }

    @SuppressWarnings("unchecked")
    private <E extends Throwable, A> A executeScoped(
            Effect.Scoped<E, A> scoped,
            ExecutionContext ctx
    ) throws E {
        try (var taskScope = new StructuredTaskScope.ShutdownOnFailure()) {
            EffectScopeRuntime scope = new EffectScopeRuntime(taskScope, this);

            // Execute the scoped body
            Effect<E, A> program = scoped.body().apply(scope);
            A result = execute(program, ctx);

            // Wait for all forked effects to complete
            taskScope.join();
            taskScope.throwIfFailed();

            return result;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelledException(e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause);
        }
    }

    private record DefaultCancellationHandle(Thread executionThread, CompletableFuture<Void> completionFuture,
                                             AtomicBoolean cancelled) implements CancellationHandle {

        @Override
            public void cancel() {
                if (cancelled.compareAndSet(false, true)) {
                    executionThread.interrupt();
                }
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }

            @Override
            public void await() throws InterruptedException {
                try {
                    completionFuture.get();
                } catch (ExecutionException e) {
                    // Completed with error
                }
            }

            @Override
            public boolean await(Duration timeout) throws InterruptedException {
                try {
                    completionFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                    return true;
                } catch (ExecutionException e) {
                    return true;
                } catch (TimeoutException e) {
                    return false;
                }
            }
        }
}