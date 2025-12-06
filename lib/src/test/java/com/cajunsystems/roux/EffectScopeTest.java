package com.cajunsystems.roux;

import com.cajunsystems.roux.data.Unit;
import com.cajunsystems.roux.exception.CancelledException;
import com.cajunsystems.roux.runtime.DefaultEffectRuntime;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class EffectScopeTest {
    private final DefaultEffectRuntime runtime = DefaultEffectRuntime.create();

    @Test
    void testScopedFork() throws Throwable {
        AtomicInteger counter = new AtomicInteger(0);

        Effect<Throwable, Integer> program = Effect.scoped(scope -> {
            Effect<Throwable, Integer> effect1 = Effect.suspend(() -> {
                Thread.sleep(50);
                return counter.incrementAndGet();
            });

            Effect<Throwable, Integer> effect2 = Effect.suspend(() -> {
                Thread.sleep(50);
                return counter.incrementAndGet();
            });

            return effect1.forkIn(scope).flatMap(fiber1 ->
                    effect2.forkIn(scope).flatMap(fiber2 ->
                            fiber1.join().flatMap(a ->
                                    fiber2.join().map(b -> a + b)
                            )
                    )
            );
        });

        Integer result = runtime.unsafeRun(program);
        assertEquals(3, result); // 1 + 2
        assertEquals(2, counter.get());
    }

    @Test
    void testScopeCancelsChildrenOnExit() throws Throwable {
        AtomicBoolean completed = new AtomicBoolean(false);

        Effect<Throwable, String> program = Effect.scoped(scope -> {
            Effect<Throwable, Unit> longRunning = Effect.suspend(() -> {
                try {
                    Thread.sleep(5000);
                    completed.set(true);
                    return Unit.unit();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CancelledException(e);
                }
            });

            // Fork long running task
            longRunning.forkIn(scope);

            // Return quickly - scope exits, should cancel forked task
            return Effect.succeed("done");
        });

        String result = runtime.unsafeRun(program);
        assertEquals("done", result);

        // Give some time for background task to potentially complete
        Thread.sleep(200);

        // Should NOT have completed because scope cancelled it
        assertFalse(completed.get());
    }

    @Test
    void testManualCancelAll() throws Throwable {
        AtomicInteger counter = new AtomicInteger(0);

        Effect<Throwable, Integer> program = Effect.scoped(scope -> {
            Effect<Throwable, Unit> counting = Effect.suspend(() -> {
                for (int i = 0; i < 100; i++) {
                    try {
                        Thread.sleep(10);
                        counter.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new CancelledException(e);
                    }
                }
                return Unit.unit();
            });

            return counting.forkIn(scope).flatMap(fiber ->
                    Effect.suspend(() -> {
                                try {
                                    Thread.sleep(50);
                                    return Unit.unit();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new CancelledException(e);
                                }
                            }).flatMap(_ -> scope.cancelAll())
                            .map(_ -> counter.get())
            );
        });

        Integer result = runtime.unsafeRun(program);

        // Counter should be less than 100 because we cancelled
        assertTrue(result < 100);
        assertTrue(result > 0); // But it started counting
    }

    @Test
    void testNestedScopes() throws Throwable {
        Effect<Throwable, String> program = Effect.scoped(outerScope -> {
            return Effect.scoped(innerScope -> {
                Effect<Throwable, String> inner = Effect.succeed("inner")
                        .forkIn(innerScope)
                        .flatMap(Fiber::join);

                return inner.map(s -> s + "-outer");
            });
        });

        String result = runtime.unsafeRun(program);
        assertEquals("inner-outer", result);
    }
}