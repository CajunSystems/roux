package com.cajunsystems.roux.runtime;

import com.cajunsystems.roux.CancellationHandle;
import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.EffectRuntime;
import com.cajunsystems.roux.Fiber;
import com.cajunsystems.roux.capability.Capability;
import com.cajunsystems.roux.capability.CapabilityHandler;
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
    private final boolean useTrampoline;

    public DefaultEffectRuntime(ExecutorService executor, boolean useTrampoline) {
        this.executor = executor;
        this.useTrampoline = useTrampoline;
    }

    public static DefaultEffectRuntime create() {
        return new DefaultEffectRuntime(
                Executors.newVirtualThreadPerTaskExecutor(),
                true  // Use trampoline by default for stack safety
        );
    }

    public static DefaultEffectRuntime createDirect() {
        return new DefaultEffectRuntime(
                Executors.newVirtualThreadPerTaskExecutor(),
                false  // Direct recursion (legacy)
        );
    }

    @Override
    public <E extends Throwable, A> A unsafeRun(Effect<E, A> effect) throws E {
        if (useTrampoline) {
            return executeTrampoline(effect, ExecutionContext.root());
        } else {
            return execute(effect, ExecutionContext.root());
        }
    }

    @Override
    public <E extends Throwable, A> A unsafeRunWithHandler(
            Effect<E, A> effect,
            CapabilityHandler<Capability<?>> handler
    ) throws E {
        ExecutionContext ctx = ExecutionContext.root().withCapabilityHandler(handler);
        if (useTrampoline) {
            return executeTrampoline(effect, ctx);
        } else {
            return execute(effect, ctx);
        }
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
        return executeFork(fork, ExecutionContext.root());
    }

    private <E extends Throwable, A> Fiber<E, A> executeFork(
            Effect.Fork<E, A> fork,
            ExecutionContext parentCtx
    ) {
        CompletableFuture<Either<Throwable, A>> resultFuture = new CompletableFuture<>();
        AtomicReference<Thread> threadRef = new AtomicReference<>();
        UUID fiberId = UUID.randomUUID();
        AtomicBoolean interrupted = new AtomicBoolean(false);

        executor.execute(() -> {
            threadRef.set(Thread.currentThread());
            ExecutionContext childCtx = parentCtx.childContext();

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
            case Effect.Fork<?, ?> fork -> (A) executeFork((Effect.Fork<?, ?>) fork, ctx);
            case Effect.Scoped<E, A> scoped -> executeScoped(scoped, ctx);
            case Effect.Generate<E, A> generate -> executeGenerate(generate, ctx);
            case Effect.PerformCapability<E, A> perform -> executePerformCapability(perform, ctx);
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

            try {
                // Execute the scoped body
                Effect<E, A> program = scoped.body().apply(scope);
                A result = execute(program, ctx);

                // Wait for all forked effects to complete
                taskScope.join();
                taskScope.throwIfFailed();

                return result;
            } catch (Throwable t) {
                // On error, cancel all forked fibers
                try {
                    unsafeRun(scope.cancelAll());
                } catch (Throwable ignored) {
                    // Ignore cancellation errors
                }
                throw t;
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelledException(e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause);
        }
    }

    @SuppressWarnings("unchecked")
    private <E extends Throwable, A> A executeGenerate(
            Effect.Generate<E, A> generate,
            ExecutionContext ctx
    ) throws E {
        GeneratorContextImpl<E> genCtx = new GeneratorContextImpl<>(
                generate.handler(),
                this
        );
        try {
            return generate.generator().generate(genCtx);
        } catch (Throwable e) {
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw (E) e;
        }
    }

    @SuppressWarnings("unchecked")
    private <E extends Throwable, R> R executePerformCapability(
            Effect.PerformCapability<E, R> perform,
            ExecutionContext ctx
    ) throws E {
        try {
            return ctx.getCapabilityHandler().handle(perform.capability());
        } catch (Exception e) {
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw (E) e;
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

    // ========================================================================
    // Stack-Safe Trampolined Execution
    // ========================================================================

    /**
     * Trampolined execution - stack-safe for arbitrarily deep effect chains.
     */
    @SuppressWarnings("unchecked")
    private <E extends Throwable, A> A executeTrampoline(
            Effect<E, A> effect,
            ExecutionContext ctx
    ) throws E {
        Object currentValue = null;
        Effect<?, ?> currentEffect = effect;
        Throwable currentError = null;
        java.util.Deque<Continuation> continuations = new java.util.ArrayDeque<>();

        while (true) {
            // Check for cancellation
            if (Thread.interrupted()) {
                throw new CancelledException();
            }

            // Handle error propagation
            if (currentError != null) {
                if (continuations.isEmpty()) {
                    throw (E) currentError;
                }

                Continuation cont = continuations.pop();
                if (cont instanceof FoldContinuation foldCont) {
                    // Found error handler
                    try {
                        currentEffect = foldCont.onError.apply(currentError);
                        currentError = null;
                    } catch (Throwable t) {
                        currentError = t;
                    }
                } else if (cont instanceof MapErrorContinuation mapErrorCont) {
                    // Transform error
                    try {
                        currentError = mapErrorCont.f.apply(currentError);
                    } catch (Throwable t) {
                        currentError = t;
                    }
                } else {
                    // Other continuations can't handle errors, keep propagating
                    continue;
                }
            }

            // Evaluate current effect
            if (currentEffect != null) {
                try {
                    TrampolineResult result = evaluateOne(currentEffect, ctx, continuations);

                    if (result.isDone) {
                        currentValue = result.value;
                        currentEffect = null;

                        // Apply continuations
                        while (!continuations.isEmpty()) {
                            Continuation cont = continuations.pop();
                            if (cont instanceof FlatMapContinuation flatMapCont) {
                                currentEffect = flatMapCont.f.apply(currentValue);
                                break;
                            } else if (cont instanceof FoldContinuation foldCont) {
                                currentEffect = foldCont.onSuccess.apply(currentValue);
                                break;
                            } else if (cont instanceof MapErrorContinuation) {
                                // MapError doesn't affect success values, skip it
                                continue;
                            }
                        }
                        
                        if (currentEffect == null && continuations.isEmpty()) {
                            // No more continuations - we're done
                            return (A) currentValue;
                        }
                    } else if (result.error != null) {
                        currentError = result.error;
                        currentEffect = null;
                    } else {
                        currentEffect = result.nextEffect;
                    }
                } catch (Throwable t) {
                    currentError = t;
                    currentEffect = null;
                }
            }
        }
    }

    private static class TrampolineResult {
        final boolean isDone;
        final Object value;
        final Throwable error;
        final Effect<?, ?> nextEffect;

        static TrampolineResult done(Object value) {
            return new TrampolineResult(true, value, null, null);
        }

        static TrampolineResult error(Throwable error) {
            return new TrampolineResult(false, null, error, null);
        }

        static TrampolineResult evaluate(Effect<?, ?> effect) {
            return new TrampolineResult(false, null, null, effect);
        }

        private TrampolineResult(boolean isDone, Object value, Throwable error, Effect<?, ?> nextEffect) {
            this.isDone = isDone;
            this.value = value;
            this.error = error;
            this.nextEffect = nextEffect;
        }
    }

    private sealed interface Continuation {}

    private record FlatMapContinuation(
            java.util.function.Function<Object, Effect<?, ?>> f
    ) implements Continuation {}

    private record FoldContinuation(
            java.util.function.Function<Throwable, Effect<?, ?>> onError,
            java.util.function.Function<Object, Effect<?, ?>> onSuccess
    ) implements Continuation {}

    private record MapErrorContinuation(
            java.util.function.Function<Throwable, Throwable> f
    ) implements Continuation {}

    @SuppressWarnings("unchecked")
    private <E extends Throwable, A> TrampolineResult evaluateOne(
            Effect<E, A> effect,
            ExecutionContext ctx,
            java.util.Deque<Continuation> continuations
    ) {
        return switch (effect) {
            case Effect.Pure<E, A> pure ->
                    TrampolineResult.done(pure.value());

            case Effect.Fail<E, A> fail ->
                    TrampolineResult.error(fail.error());

            case Effect.Suspend<E, A> suspend -> {
                try {
                    A result = suspend.thunk().get();
                    yield TrampolineResult.done(result);
                } catch (Exception e) {
                    yield TrampolineResult.error(e);
                }
            }

            case Effect.FlatMap<E, ?, A> flatMap -> {
                // Push continuation and evaluate source
                java.util.function.Function<?, ?> f = flatMap.f();
                continuations.push(new FlatMapContinuation(
                        (java.util.function.Function<Object, Effect<?, ?>>) f
                ));
                yield TrampolineResult.evaluate(flatMap.source());
            }

            case Effect.Fold<?, E, ?, A> fold -> {
                // Push fold continuation and evaluate source
                java.util.function.Function<?, ?> onError = fold.onError();
                java.util.function.Function<?, ?> onSuccess = fold.onSuccess();
                continuations.push(new FoldContinuation(
                        (java.util.function.Function<Throwable, Effect<?, ?>>) onError,
                        (java.util.function.Function<Object, Effect<?, ?>>) onSuccess
                ));
                yield TrampolineResult.evaluate(fold.source());
            }

            case Effect.MapError<?, E, A> mapError -> {
                // Push error mapping continuation
                java.util.function.Function<?, ?> f = mapError.f();
                continuations.push(new MapErrorContinuation(
                        (java.util.function.Function<Throwable, Throwable>) f
                ));
                yield TrampolineResult.evaluate(mapError.source());
            }

            case Effect.Fork<?, ?> fork -> {
                try {
                    Fiber<?, ?> fiber = executeFork((Effect.Fork<?, ?>) fork, ctx);
                    yield TrampolineResult.done(fiber);
                } catch (Throwable t) {
                    yield TrampolineResult.error(t);
                }
            }

            case Effect.Scoped<E, A> scoped -> {
                try {
                    A result = executeScoped(scoped, ctx);
                    yield TrampolineResult.done(result);
                } catch (Throwable t) {
                    yield TrampolineResult.error(t);
                }
            }

            case Effect.Generate<E, A> generate -> {
                try {
                    A result = executeGenerate(generate, ctx);
                    yield TrampolineResult.done(result);
                } catch (Throwable t) {
                    yield TrampolineResult.error(t);
                }
            }

            case Effect.PerformCapability<E, A> perform -> {
                try {
                    A result = executePerformCapability(perform, ctx);
                    yield TrampolineResult.done(result);
                } catch (Throwable t) {
                    yield TrampolineResult.error(t);
                }
            }
        };
    }
}