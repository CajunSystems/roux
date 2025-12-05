package com.cajunsystems.roux;

import com.cajunsystems.roux.data.Either;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class EffectTest {
    private final EffectRuntime runtime = EffectRuntime.defaultRuntime();

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
        Effect<Exception, Integer> effect = Effect.succeed(1)
                .map(x -> x + 1)
                .flatMap(x -> Effect.succeed(x * 2))
                .map(x -> x + 10)
                .catchAll(e -> Effect.succeed(0));

        Integer result = runtime.unsafeRun(effect);
        assertEquals(14, result); // ((1 + 1) * 2) + 10
    }
}