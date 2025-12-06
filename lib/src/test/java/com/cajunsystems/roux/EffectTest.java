package com.cajunsystems.roux;

import com.cajunsystems.roux.data.Either;
import com.cajunsystems.roux.data.Unit;
import com.cajunsystems.roux.runtime.DefaultEffectRuntime;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class EffectTest {
    private final EffectRuntime runtime = DefaultEffectRuntime.create();

    @Test
    void testPureEffect() throws Throwable {
        Effect<Throwable, Integer> effect = Effect.succeed(42);
        Integer result = runtime.unsafeRun(effect);
        assertEquals(42, result);
    }

    @Test
    void testFailEffect() {
        Effect<IOException, String> effect = Effect.fail(new IOException("boom"));

        IOException thrown = assertThrows(
                IOException.class,
                () -> runtime.unsafeRun(effect)
        );
        assertEquals("boom", thrown.getMessage());
    }

    @Test
    void testSuspendEffect() throws Throwable {
        AtomicInteger counter = new AtomicInteger(0);

        Effect<Throwable, Integer> effect = Effect.suspend(counter::incrementAndGet);

        // Lazy - not evaluated yet
        assertEquals(0, counter.get());

        // Now evaluated
        Integer result = runtime.unsafeRun(effect);
        assertEquals(1, result);
        assertEquals(1, counter.get());
    }

    @Test
    void testMapEffect() throws Throwable {
        Effect<Throwable, Integer> effect = Effect.succeed(10)
                .map(x -> x * 2);

        Integer result = runtime.unsafeRun(effect);
        assertEquals(20, result);
    }

    @Test
    void testFlatMapEffect() throws Throwable {
        Effect<Throwable, Integer> effect = Effect.succeed(5)
                .flatMap(x -> Effect.succeed(x + 3))
                .flatMap(y -> Effect.succeed(y * 2));

        Integer result = runtime.unsafeRun(effect);
        assertEquals(16, result);
    }

    @Test
    void testCatchAll() throws Throwable {
        Effect<IOException, String> failing = Effect.fail(new IOException("error"));

        Effect<IOException, String> recovered = failing.catchAll(e ->
                Effect.succeed("recovered: " + e.getMessage())
        );

        String result = runtime.unsafeRun(recovered);
        assertEquals("recovered: error", result);
    }

    @Test
    void testMapError() {
        Effect<IOException, String> original = Effect.fail(new IOException("io error"));

        Effect<RuntimeException, String> mapped = original.mapError(
                io -> new RuntimeException("wrapped: " + io.getMessage())
        );

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> runtime.unsafeRun(mapped)
        );
        assertEquals("wrapped: io error", thrown.getMessage());
    }

    @Test
    void testOrElse() throws Throwable {
        Effect<Exception, String> failing = Effect.fail(new Exception("fail"));
        Effect<Exception, String> fallback = Effect.succeed("fallback value");

        Effect<Exception, String> effect = failing.orElse(fallback);

        String result = runtime.unsafeRun(effect);
        assertEquals("fallback value", result);
    }

    @Test
    void testAttempt() throws Throwable {
        Effect<IOException, String> success = Effect.succeed("hello");
        Effect<Throwable, Either<IOException, String>> attempted = success.attempt();

        Either<IOException, String> result = runtime.unsafeRun(attempted);
        assertInstanceOf(Either.Right.class, result);
        assertEquals("hello", ((Either.Right<IOException, String>) result).value());

        Effect<IOException, String> failure = Effect.fail(new IOException("error"));
        Either<IOException, String> failResult = runtime.unsafeRun(failure.attempt());

        assertInstanceOf(Either.Left.class, failResult);
        assertEquals("error", ((Either.Left<IOException, String>) failResult).value().getMessage());
    }

    @Test
    void testComposition() throws Throwable {
        Effect<Throwable, Integer> effect = Effect.succeed(1)
                .map(x -> x + 1)
                .flatMap(x -> Effect.succeed(x * 2))
                .map(x -> x + 10)
                .catchAll(e -> Effect.succeed(0));

        Integer result = runtime.unsafeRun(effect);
        assertEquals(14, result); // ((1 + 1) * 2) + 10
    }

    @Test
    void testFork() throws Throwable {
        Effect<Throwable, Integer> computation = Effect.suspend(() -> {
            Thread.sleep(100);
            return 42;
        });

        Effect<Throwable, Integer> program = computation.fork()
                .flatMap(Fiber::join);

        Integer result = runtime.unsafeRun(program);
        assertEquals(42, result);
    }

    @Test
    void testFiberInterrupt() throws Throwable {
        AtomicBoolean completed = new AtomicBoolean(false);

        Effect<Throwable, String> longRunning = Effect.suspend(() -> {
            Thread.sleep(5000);
            completed.set(true);
            return "done";
        });

        Effect<Throwable, Unit> program = longRunning.fork()
                .flatMap(fiber -> Effect.suspend(() -> {
                    Thread.sleep(100);
                    return Unit.unit();
                }).flatMap(_ -> fiber.interrupt()));

        runtime.unsafeRun(program);

        Thread.sleep(200);
        assertFalse(completed.get());
    }

    @Test
    void testMultipleForks() throws Throwable {
        Effect<Throwable, Integer> effect1 = Effect.succeed(1);
        Effect<Throwable, Integer> effect2 = Effect.succeed(2);
        Effect<Throwable, Integer> effect3 = Effect.succeed(3);

        Effect<Throwable, Integer> program = effect1.fork()
                .flatMap(fiber1 -> effect2.fork()
                        .flatMap(fiber2 -> effect3.fork()
                                .flatMap(fiber3 -> fiber1.join()
                                        .flatMap(a -> fiber2.join()
                                                .flatMap(b -> fiber3.join()
                                                        .map(c -> a + b + c))))));

        Integer result = runtime.unsafeRun(program);
        assertEquals(6, result);
    }

    @Test
    void testRunAsync() throws InterruptedException {
        AtomicInteger result = new AtomicInteger(0);
        AtomicBoolean errorOccurred = new AtomicBoolean(false);

        Effect<Throwable, Integer> effect = Effect.suspend(() -> {
            Thread.sleep(100);
            return 42;
        });

        CancellationHandle handle = runtime.runAsync(
                effect,
                result::set,
                error -> errorOccurred.set(true)
        );

        handle.await();
        assertEquals(42, result.get());
        assertFalse(errorOccurred.get());
    }

    @Test
    void testCancellation() throws InterruptedException {
        AtomicBoolean started = new AtomicBoolean(false);
        AtomicBoolean completed = new AtomicBoolean(false);

        Effect<Throwable, String> longRunning = Effect.suspend(() -> {
            started.set(true);
            return "never";
        }).flatMap(s -> Effect.suspend(() -> {
            Thread.sleep(5000);
            return "completed";
        }));

        CancellationHandle handle = runtime.runAsync(
                longRunning,
                result -> completed.set(true),
                error -> {
                }
        );

        Thread.sleep(100);
        assertTrue(started.get());
        assertFalse(completed.get());

        handle.cancel();
        assertTrue(handle.isCancelled());

        Thread.sleep(500);
        assertFalse(completed.get());
    }

    @Test
    void testAwaitWithTimeout() throws InterruptedException {
        Effect<Throwable, String> effect = Effect.suspend(() -> {
            Thread.sleep(200);
            return "done";
        });

        AtomicInteger result = new AtomicInteger(0);
        CancellationHandle handle = runtime.runAsync(
                effect,
                r -> result.set(1),
                error -> {
                }
        );

        boolean completed = handle.await(java.time.Duration.ofMillis(50));
        assertFalse(completed); // Should timeout

        handle.await(); // Wait for actual completion
        assertEquals(1, result.get());
    }

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
        assertEquals(3, result);
    }

    @Test
    void testErrorInFlatMapChain() {
        Effect<Throwable, Integer> effect = Effect.succeed(1)
                .flatMap(x -> Effect.succeed(x + 1))
                .flatMap(x -> Effect.<Throwable, Integer>fail(new RuntimeException("boom")))
                .flatMap(x -> Effect.succeed(x * 2));

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> runtime.unsafeRun(effect)
        );
        assertEquals("boom", thrown.getMessage());
    }

    @Test
    void testCatchAllInChain() throws Throwable {
        Effect<Throwable, Integer> effect = Effect.succeed(1)
                .flatMap(x -> Effect.<Throwable, Integer>fail(new RuntimeException("error")))
                .catchAll(e -> Effect.succeed(99))
                .map(x -> x + 1);

        Integer result = runtime.unsafeRun(effect);
        assertEquals(100, result);
    }

    @Test
    void testConcurrentEffects() throws Throwable {
        AtomicInteger counter = new AtomicInteger(0);

        Effect<Throwable, Integer> increment = Effect.suspend(() -> {
            Thread.sleep(50);
            return counter.incrementAndGet();
        });

        // Fork 5 concurrent increments
        Effect<Throwable, Integer> program = increment.fork()
                .flatMap(f1 -> increment.fork()
                        .flatMap(f2 -> increment.fork()
                                .flatMap(f3 -> increment.fork()
                                        .flatMap(f4 -> increment.fork()
                                                .flatMap(f5 ->
                                                        f1.join().flatMap(a ->
                                                                f2.join().flatMap(b ->
                                                                        f3.join().flatMap(c ->
                                                                                f4.join().flatMap(d ->
                                                                                        f5.join().map(e -> a + b + c + d + e)
                                                                                )))))))));

        Integer result = runtime.unsafeRun(program);
        assertEquals(15, result); // 1+2+3+4+5
    }


    @Test
    void testRunAsyncWithError() throws InterruptedException {
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicBoolean successCalled = new AtomicBoolean(false);

        Effect<RuntimeException, Integer> effect = Effect.fail(new RuntimeException("async error"));

        CancellationHandle handle = runtime.runAsync(
                effect,
                r -> successCalled.set(true),
                error::set
        );

        handle.await();
        assertFalse(successCalled.get());
        assertNotNull(error.get());
        assertEquals("async error", error.get().getMessage());
    }

    @Test
    void testCancelBeforeStart() throws InterruptedException {
        AtomicBoolean executed = new AtomicBoolean(false);

        Effect<Throwable, Integer> effect = Effect.suspend(() -> {
            executed.set(true);
            return 42;
        });

        CancellationHandle handle = runtime.runAsync(
                effect,
                r -> {
                },
                error -> {
                }
        );

        // Cancel immediately
        handle.cancel();

        Thread.sleep(100);
        // Might or might not have executed depending on timing
        assertTrue(handle.isCancelled());
    }

    @Test
    void testMultipleAwaits() throws InterruptedException {
        Effect<Throwable, Integer> effect = Effect.succeed(42);

        AtomicInteger result = new AtomicInteger(0);
        CancellationHandle handle = runtime.runAsync(
                effect,
                result::set,
                error -> {
                }
        );

        handle.await();
        handle.await(); // Should work fine to await multiple times

        assertEquals(42, result.get());
    }

    @Test
    void testNestedCatchAll() throws Throwable {
        Effect<Throwable, Integer> effect = Effect.succeed(1)
                .flatMap(x -> Effect.<Throwable, Integer>fail(new RuntimeException("error1")))
                .catchAll(e -> Effect.<Throwable, Integer>fail(new RuntimeException("error2")))
                .catchAll(e -> Effect.succeed(99));

        Integer result = runtime.unsafeRun(effect);
        assertEquals(99, result);
    }

    @Test
    void testMapErrorAndCatchAll() throws Throwable {
        Effect<IOException, Integer> effect = Effect.fail(new IOException("io error"));

        Effect<RuntimeException, Integer> transformed = effect
                .mapError(io -> new RuntimeException("wrapped: " + io.getMessage()))
                .catchAll(e -> Effect.succeed(42));

        Integer result = runtime.unsafeRun(transformed);
        assertEquals(42, result);
    }

    @Test
    void testAttemptSuccess() throws Throwable {
        Effect<IOException, Integer> effect = Effect.succeed(42);

        Effect<Throwable, Either<IOException, Integer>> attempted = effect.attempt();
        Either<IOException, Integer> result = runtime.unsafeRun(attempted);

        assertInstanceOf(Either.Right.class, result);
        assertEquals(42, ((Either.Right<IOException, Integer>) result).value());
    }

    @Test
    void testAttemptFailure() throws Throwable {
        Effect<IOException, Integer> effect = Effect.fail(new IOException("error"));

        Effect<Throwable, Either<IOException, Integer>> attempted = effect.attempt();
        Either<IOException, Integer> result = runtime.unsafeRun(attempted);

        assertInstanceOf(Either.Left.class, result);
        assertEquals("error", ((Either.Left<IOException, Integer>) result).value().getMessage());
    }

    @Test
    void testOrElseChain() throws Throwable {
        Effect<RuntimeException, Integer> effect =
                Effect.<RuntimeException, Integer>fail(new RuntimeException("1"))
                        .orElse(Effect.<RuntimeException, Integer>fail(new RuntimeException("2")))
                        .orElse(Effect.<RuntimeException, Integer>fail(new RuntimeException("3")))
                        .orElse(Effect.succeed(99));

        Integer result = runtime.unsafeRun(effect);
        assertEquals(99, result);
    }

    @Test
    void testSuspendIsLazy() {
        AtomicBoolean executed = new AtomicBoolean(false);

        Effect<Throwable, Integer> effect = Effect.suspend(() -> {
            executed.set(true);
            return 42;
        });

        // Just creating the effect shouldn't execute it
        assertFalse(executed.get());

        // Still not executed
        Effect<Throwable, Integer> mapped = effect.map(x -> x * 2);
        assertFalse(executed.get());
    }

    @Test
    void testFlatMapIsLazy() {
        AtomicInteger counter = new AtomicInteger(0);

        Effect<Throwable, Integer> effect = Effect.succeed(1)
                .flatMap(x -> Effect.suspend(() -> {
                    counter.incrementAndGet();
                    return x + 1;
                }));

        // Creating the chain doesn't execute
        assertEquals(0, counter.get());
    }

    @Test
    void testMultipleRunsExecuteIndependently() throws Throwable {
        AtomicInteger counter = new AtomicInteger(0);

        Effect<Throwable, Integer> effect = Effect.suspend(counter::incrementAndGet);

        Integer result1 = runtime.unsafeRun(effect);
        Integer result2 = runtime.unsafeRun(effect);
        Integer result3 = runtime.unsafeRun(effect);

        assertEquals(1, result1);
        assertEquals(2, result2);
        assertEquals(3, result3);
        assertEquals(3, counter.get());
    }

    @Test
    void testDeepFlatMapChain() throws Throwable {
        Effect<Throwable, Integer> effect = Effect.succeed(0);

        for (int i = 0; i < 100; i++) {
            final int value = i;
            effect = effect.flatMap(x -> Effect.succeed(x + 1));
        }

        Integer result = runtime.unsafeRun(effect);
        assertEquals(100, result);
    }

    @Test
    void testMixedOperations() throws Throwable {
        AtomicInteger sideEffect = new AtomicInteger(0);

        Effect<Throwable, Integer> effect = Effect.succeed(10)
                .map(x -> x * 2)                           // 20
                .flatMap(x -> Effect.suspend(() -> {
                    sideEffect.incrementAndGet();
                    return x + 5;                          // 25
                }))
                .map(x -> x / 5)                           // 5
                .flatMap(x -> Effect.succeed(x * 3))       // 15
                .catchAll(e -> Effect.succeed(0))
                .map(x -> x + 10);                         // 25

        Integer result = runtime.unsafeRun(effect);
        assertEquals(25, result);
        assertEquals(1, sideEffect.get());
    }

    @Test
    void testEffectInEffect() throws Throwable {
        Effect<Throwable, Effect<Throwable, Integer>> nested =
                Effect.succeed(Effect.succeed(42));

        Effect<Throwable, Integer> flattened = nested.flatMap(inner -> inner);

        Integer result = runtime.unsafeRun(flattened);
        assertEquals(42, result);
    }
}