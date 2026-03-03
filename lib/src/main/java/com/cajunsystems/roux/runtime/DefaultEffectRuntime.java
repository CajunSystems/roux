package com.cajunsystems.roux.runtime;

import com.cajunsystems.roux.CancellationHandle;
import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.EffectRuntime;
import com.cajunsystems.roux.Fiber;
import com.cajunsystems.roux.capability.Capability;
import com.cajunsystems.roux.capability.CapabilityHandler;
import com.cajunsystems.roux.data.Either;
import com.cajunsystems.roux.exception.CancelledException;
import com.cajunsystems.roux.exception.MissingCapabilityHandlerException;
import com.cajunsystems.roux.exception.TimeoutException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class DefaultEffectRuntime implements EffectRuntime, AutoCloseable {
    private final ExecutorService executor;
    private final boolean useTrampoline;

    public DefaultEffectRuntime(ExecutorService executor, boolean useTrampoline) {
        this.executor = executor;
        this.useTrampoline = useTrampoline;
    }

    public static DefaultEffectRuntime create() {
        return new DefaultEffectRuntime(
                Executors.newVirtualThreadPerTaskExecutor(),
                true
        );
    }

    /** Legacy non-trampolined runtime. Prefer {@link #create()}. */
    public static DefaultEffectRuntime createDirect() {
        return new DefaultEffectRuntime(
                Executors.newVirtualThreadPerTaskExecutor(),
                false
        );
    }

    // -----------------------------------------------------------------------
    // AutoCloseable
    // -----------------------------------------------------------------------

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // -----------------------------------------------------------------------
    // EffectRuntime
    // -----------------------------------------------------------------------

    @Override
    public <E extends Throwable, A> A unsafeRun(Effect<E, A> effect) throws E {
        return runEffect(effect, ExecutionContext.root());
    }

    @Override
    public <E extends Throwable, A> A unsafeRunWithHandler(
            Effect<E, A> effect,
            CapabilityHandler<Capability<?>> handler
    ) throws E {
        return runEffect(effect, ExecutionContext.root().withCapabilityHandler(handler));
    }

    @Override
    public <E extends Throwable, A> CancellationHandle runAsync(
            Effect<E, A> effect,
            Consumer<A> onSuccess,
            Consumer<Throwable> onError
    ) {
        CompletableFuture<Void> completionFuture = new CompletableFuture<>();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        // Use a latch so we can hand back the handle without spinning
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Thread> threadRef = new AtomicReference<>();

        executor.execute(() -> {
            threadRef.set(Thread.currentThread());
            started.countDown();

            try {
                // Use the stack-safe trampoline even for async paths
                A result = executeTrampoline(effect, ExecutionContext.root());
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

        try {
            started.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return new DefaultCancellationHandle(threadRef.get(), completionFuture, cancelled);
    }

    @Override
    public <E extends Throwable, A> Fiber<E, A> executeFork(Effect.Fork<E, A> fork) {
        return executeFork(fork, ExecutionContext.root());
    }

    <E extends Throwable, A> Fiber<E, A> executeFork(
            Effect.Fork<E, A> fork,
            ExecutionContext parentCtx
    ) {
        CompletableFuture<Either<Throwable, A>> resultFuture = new CompletableFuture<>();
        UUID fiberId = UUID.randomUUID();
        AtomicBoolean interrupted = new AtomicBoolean(false);
        // Use a latch so executeFork returns only after the thread is actually running
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Thread> threadRef = new AtomicReference<>();

        executor.execute(() -> {
            threadRef.set(Thread.currentThread());
            started.countDown();

            ExecutionContext childCtx = parentCtx.childContext();
            try {
                // Use the stack-safe trampoline for forked effects too
                A result = executeTrampoline(fork.effect(), childCtx);
                resultFuture.complete(Either.right(result));
            } catch (Throwable error) {
                resultFuture.complete(Either.left(error));
            }
        });

        try {
            started.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return new FiberRuntime<>(fiberId, resultFuture, threadRef.get(), interrupted);
    }

    @Override
    public Executor executor() {
        return executor;
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private <E extends Throwable, A> A runEffect(Effect<E, A> effect, ExecutionContext ctx) throws E {
        if (useTrampoline) {
            return executeTrampoline(effect, ctx);
        } else {
            return execute(effect, ctx);
        }
    }

    // -----------------------------------------------------------------------
    // Recursive (non-stack-safe) execution — kept for reference / legacy
    // -----------------------------------------------------------------------

    private <E extends Throwable, A> A execute(
            Effect<E, A> effect,
            ExecutionContext ctx
    ) throws E {
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
                    if (e instanceof RuntimeException re) throw re;
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
            case Effect.Timeout<?, ?> timeout -> (A) executeTimeout((Effect.Timeout<?, ?>) timeout, ctx);
            case Effect.Race<?, ?> race -> (A) executeRace((Effect.Race<?, ?>) race, ctx);
        };
    }

    @SuppressWarnings("unchecked")
    private <E extends Throwable, A, B> B executeFlatMap(
            Effect.FlatMap<E, A, B> flatMap,
            ExecutionContext ctx
    ) throws E {
        A intermediate = (A) execute((Effect<E, A>) flatMap.source(), ctx);
        return execute(flatMap.f().apply(intermediate), ctx);
    }

    @SuppressWarnings("unchecked")
    private <E extends Throwable, E2 extends Throwable, A, B> B executeFold(
            Effect.Fold<E, E2, A, B> fold,
            ExecutionContext ctx
    ) throws E2 {
        try {
            A result = (A) execute((Effect<E, A>) fold.source(), ctx);
            return execute(fold.onSuccess().apply(result), ctx);
        } catch (CancelledException e) {
            throw e;
        } catch (Throwable error) {
            @SuppressWarnings("unchecked") E typedError = (E) error;
            return execute(fold.onError().apply(typedError), ctx);
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
            @SuppressWarnings("unchecked") E typedError = (E) error;
            throw mapError.f().apply(typedError);
        }
    }

    @SuppressWarnings("unchecked")
    private <E extends Throwable, A> A executeScoped(
            Effect.Scoped<E, A> scoped,
            ExecutionContext ctx
    ) throws E {
        try (var taskScope = new StructuredTaskScope.ShutdownOnFailure()) {
            EffectScopeRuntime scope = new EffectScopeRuntime(taskScope, this, ctx);

            try {
                Effect<E, A> program = scoped.body().apply(scope);
                A result = execute(program, ctx);

                taskScope.join();
                taskScope.throwIfFailed();

                return result;
            } catch (Throwable t) {
                try {
                    unsafeRun(scope.cancelAll());
                } catch (Throwable ignored) { }
                throw t;
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelledException(e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        }
    }

    @SuppressWarnings("unchecked")
    private <E extends Throwable, A> A executeGenerate(
            Effect.Generate<E, A> generate,
            ExecutionContext ctx
    ) throws E {
        GeneratorContextImpl<E> genCtx = new GeneratorContextImpl<>(generate.handler(), this);
        try {
            return generate.generator().generate(genCtx);
        } catch (Throwable e) {
            if (e instanceof RuntimeException re) throw re;
            throw (E) e;
        }
    }

    @SuppressWarnings("unchecked")
    private <E extends Throwable, R> R executePerformCapability(
            Effect.PerformCapability<E, R> perform,
            ExecutionContext ctx
    ) throws E {
        Capability<R> capability = perform.capability();
        try {
            return ctx.getCapabilityHandler().handle(capability);
        } catch (MissingCapabilityHandlerException e) {
            throw new MissingCapabilityHandlerException(
                    e.getMessage() + " Capability attempted: " + capability.getClass().getName(),
                    e
            );
        } catch (Exception e) {
            if (e instanceof RuntimeException re) throw re;
            throw (E) e;
        }
    }

    @SuppressWarnings("unchecked")
    private <E extends Throwable, A> A executeTimeout(
            Effect.Timeout<E, A> timeout,
            ExecutionContext ctx
    ) {
        Duration duration = timeout.duration();
        CompletableFuture<A> future = new CompletableFuture<>();

        executor.execute(() -> {
            try {
                A result = executeTrampoline(timeout.effect(), ctx);
                future.complete(result);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });

        try {
            return future.get(duration.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            throw new TimeoutException(duration);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelledException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private <E extends Throwable, A> A executeRace(
            Effect.Race<E, A> race,
            ExecutionContext ctx
    ) {
        List<Effect<E, A>> effects = (List<Effect<E, A>>) race.effects();
        if (effects.isEmpty()) {
            throw new IllegalArgumentException("race() requires at least one effect");
        }

        CompletableFuture<Either<Throwable, A>> winner = new CompletableFuture<>();
        List<CompletableFuture<Void>> tasks = new ArrayList<>();

        for (Effect<E, A> effect : effects) {
            CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
                try {
                    A result = executeTrampoline(effect, ctx);
                    winner.complete(Either.right(result));
                } catch (Throwable t) {
                    winner.complete(Either.left(t));
                }
            }, executor);
            tasks.add(task);
        }

        try {
            Either<Throwable, A> result = winner.get();
            // Cancel remaining tasks
            tasks.forEach(t -> t.cancel(true));
            return switch (result) {
                case Either.Right<Throwable, A> right -> right.value();
                case Either.Left<Throwable, A> left -> {
                    Throwable err = left.value();
                    if (err instanceof RuntimeException re) throw re;
                    throw new RuntimeException(err);
                }
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelledException(e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        }
    }

    // -----------------------------------------------------------------------
    // CancellationHandle
    // -----------------------------------------------------------------------

    private record DefaultCancellationHandle(
            Thread executionThread,
            CompletableFuture<Void> completionFuture,
            AtomicBoolean cancelled
    ) implements CancellationHandle {

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
                // Completed with error — that's fine for await()
            }
        }

        @Override
        public boolean await(Duration timeout) throws InterruptedException {
            try {
                completionFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                return true;
            } catch (ExecutionException e) {
                return true;
            } catch (java.util.concurrent.TimeoutException e) {
                return false;
            }
        }
    }

    // ========================================================================
    // Stack-Safe Trampolined Execution
    // ========================================================================

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
            if (Thread.interrupted()) {
                throw new CancelledException();
            }

            // Error propagation
            if (currentError != null) {
                if (continuations.isEmpty()) {
                    throw (E) currentError;
                }

                Continuation cont = continuations.pop();
                if (cont instanceof FoldContinuation foldCont) {
                    try {
                        currentEffect = foldCont.onError.apply(currentError);
                        currentError = null;
                    } catch (Throwable t) {
                        currentError = t;
                    }
                } else if (cont instanceof MapErrorContinuation mapErrorCont) {
                    try {
                        currentError = mapErrorCont.f.apply(currentError);
                    } catch (Throwable t) {
                        currentError = t;
                    }
                } else {
                    // Non-error continuations: keep propagating
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

                        while (!continuations.isEmpty()) {
                            Continuation cont = continuations.pop();
                            if (cont instanceof FlatMapContinuation flatMapCont) {
                                currentEffect = flatMapCont.f.apply(currentValue);
                                break;
                            } else if (cont instanceof FoldContinuation foldCont) {
                                currentEffect = foldCont.onSuccess.apply(currentValue);
                                break;
                            } else if (cont instanceof MapErrorContinuation) {
                                continue;
                            }
                        }

                        if (currentEffect == null && continuations.isEmpty()) {
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
                    yield TrampolineResult.done(suspend.thunk().get());
                } catch (Exception e) {
                    yield TrampolineResult.error(e);
                }
            }

            case Effect.FlatMap<E, ?, A> flatMap -> {
                java.util.function.Function<?, ?> f = flatMap.f();
                continuations.push(new FlatMapContinuation(
                        (java.util.function.Function<Object, Effect<?, ?>>) f
                ));
                yield TrampolineResult.evaluate(flatMap.source());
            }

            case Effect.Fold<?, E, ?, A> fold -> {
                java.util.function.Function<?, ?> onError = fold.onError();
                java.util.function.Function<?, ?> onSuccess = fold.onSuccess();
                continuations.push(new FoldContinuation(
                        (java.util.function.Function<Throwable, Effect<?, ?>>) onError,
                        (java.util.function.Function<Object, Effect<?, ?>>) onSuccess
                ));
                yield TrampolineResult.evaluate(fold.source());
            }

            case Effect.MapError<?, E, A> mapError -> {
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
                    yield TrampolineResult.done(executeScoped(scoped, ctx));
                } catch (Throwable t) {
                    yield TrampolineResult.error(t);
                }
            }

            case Effect.Generate<E, A> generate -> {
                try {
                    yield TrampolineResult.done(executeGenerate(generate, ctx));
                } catch (Throwable t) {
                    yield TrampolineResult.error(t);
                }
            }

            case Effect.PerformCapability<E, A> perform -> {
                try {
                    yield TrampolineResult.done(executePerformCapability(perform, ctx));
                } catch (Throwable t) {
                    yield TrampolineResult.error(t);
                }
            }

            case Effect.Timeout<?, ?> timeout -> {
                try {
                    yield TrampolineResult.done(executeTimeout((Effect.Timeout<?, ?>) timeout, ctx));
                } catch (Throwable t) {
                    yield TrampolineResult.error(t);
                }
            }

            case Effect.Race<?, ?> race -> {
                try {
                    yield TrampolineResult.done(executeRace((Effect.Race<?, ?>) race, ctx));
                } catch (Throwable t) {
                    yield TrampolineResult.error(t);
                }
            }
        };
    }
}
