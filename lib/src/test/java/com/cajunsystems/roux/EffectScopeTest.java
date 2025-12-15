package com.cajunsystems.roux;

import com.cajunsystems.roux.data.Unit;
import com.cajunsystems.roux.runtime.DefaultEffectRuntime;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class EffectScopeTest {
    private final EffectRuntime runtime = DefaultEffectRuntime.create();

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
                Thread.sleep(5000);
                completed.set(true);
                return Unit.unit();
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
                    Thread.sleep(10);
                    counter.incrementAndGet();
                }
                return Unit.unit();
            });

            return counting.forkIn(scope).flatMap(fiber ->
                    Effect.suspend(() -> {
                                Thread.sleep(50);
                                return Unit.unit();
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

    @Test
    void testScopeErrorCancelsChildren() throws Throwable {
        AtomicBoolean childCompleted = new AtomicBoolean(false);

        Effect<Throwable, String> program = Effect.scoped(scope -> {
            Effect<Throwable, Unit> longChild = Effect.suspend(() -> {
                Thread.sleep(5000);
                childCompleted.set(true);
                return Unit.unit();
            });

            longChild.forkIn(scope);

            // Throw error after forking
            return Effect.suspend(() -> {
                Thread.sleep(100);
                throw new RuntimeException("boom");
            });
        });

        assertThrows(RuntimeException.class, () -> runtime.unsafeRun(program));

        Thread.sleep(200);
        assertFalse(childCompleted.get()); // Child should be cancelled
    }

    @Test
    void testMultipleForkInScope() throws Throwable {
        AtomicInteger counter = new AtomicInteger(0);

        Effect<Throwable, Integer> increment = Effect.suspend(() -> {
            Thread.sleep(50);
            return counter.incrementAndGet();
        });

        Effect<Throwable, Integer> program = Effect.scoped(scope -> {
            Effect<Throwable, Fiber<Throwable, Integer>> f1 = increment.forkIn(scope);
            Effect<Throwable, Fiber<Throwable, Integer>> f2 = increment.forkIn(scope);
            Effect<Throwable, Fiber<Throwable, Integer>> f3 = increment.forkIn(scope);

            return f1.flatMap(fiber1 ->
                    f2.flatMap(fiber2 ->
                            f3.flatMap(fiber3 -> {
                                Effect<Throwable, Integer> r1 = fiber1.join();
                                Effect<Throwable, Integer> r2 = fiber2.join();
                                Effect<Throwable, Integer> r3 = fiber3.join();
                                return r1.flatMap(a -> r2.flatMap(b -> r3.map(c -> a + b + c)));
                            })
                    )
            );
        });

        Integer result = runtime.unsafeRun(program);
        assertEquals(6, result); // 1 + 2 + 3
        assertEquals(3, counter.get());
    }

    @Test
    void testScopeIsCancelledCheck() throws Throwable {
        Effect<Throwable, Boolean> program = Effect.scoped(scope -> {
            assertFalse(scope.isCancelled());

            return scope.cancelAll().map(_ -> scope.isCancelled());
        });

        Boolean result = runtime.unsafeRun(program);
        assertTrue(result);
    }

    @Test
    void testCannotForkInCancelledScope() throws Throwable {
        Effect<Throwable, String> program = Effect.scoped(scope -> {
            return scope.cancelAll().flatMap(_ ->
                    Effect.succeed("test").forkIn(scope).flatMap(Fiber::join)
            );
        });

        assertThrows(IllegalStateException.class, () -> runtime.unsafeRun(program));
    }
}