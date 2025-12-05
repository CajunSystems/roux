package com.cajunsystems.roux;

import com.cajunsystems.roux.exception.CancelledException;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EffectRuntime {
    private final ExecutorService executor;

    public EffectRuntime(ExecutorService executor) {
        this.executor = executor;
    }

    public static EffectRuntime defaultRuntime() {
        return new EffectRuntime(
                Executors.newVirtualThreadPerTaskExecutor()
        );
    }

    public <E extends Throwable, A> A unsafeRun(Effect<E, A> effect) throws E {
        return execute(effect, ExecutionContext.root());
    }

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
            case Effect.Suspend<E, A> suspend -> suspend.thunk().get();
            case Effect.FlatMap<E, ?, A> flatMap -> executeFlatMap(flatMap, ctx);
            case Effect.Fold<?, E, ?, A> fold -> executeFold(fold, ctx);
            case Effect.MapError<?, E, A> mapError -> executeMapError(mapError, ctx);
        };
    }

    private <E extends Throwable, A, B> B executeFlatMap(
            Effect.FlatMap<E, A, B> flatMap,
            ExecutionContext ctx
    ) throws E {
        A intermediate = execute(flatMap.source(), ctx);
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
        } catch (Throwable error) {
            // Check if it's a cancellation - propagate immediately
            if (error instanceof CancelledException) {
                throw (CancelledException) error;
            }

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
        } catch (Throwable error) {
            if (error instanceof CancelledException) {
                throw (CancelledException) error;
            }

            @SuppressWarnings("unchecked")
            E typedError = (E) error;
            throw mapError.f().apply(typedError);
        }
    }
}