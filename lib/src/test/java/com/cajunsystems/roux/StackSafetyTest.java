package com.cajunsystems.roux;

import com.cajunsystems.roux.runtime.DefaultEffectRuntime;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for stack-safe trampolined execution.
 * These tests verify that deep effect chains don't cause stack overflow.
 */
class StackSafetyTest {
    private final EffectRuntime runtime = DefaultEffectRuntime.create();

    @Test
    void testDeepFlatMapChain() throws Throwable {
        int depth = 100_000;
        
        Effect<Throwable, Integer> chain = Effect.succeed(0);
        for (int i = 0; i < depth; i++) {
            chain = chain.flatMap(x -> Effect.succeed(x + 1));
        }

        Integer result = runtime.unsafeRun(chain);
        assertEquals(depth, result);
    }

    @Test
    void testDeepRecursiveCountdown() throws Throwable {
        int n = 10_000;

        Integer result = runtime.unsafeRun(countdown(n));
        // Sum of 1 to n = n * (n + 1) / 2
        long expected = (long) n * (n + 1) / 2;
        assertEquals(expected, result.longValue());
    }

    private Effect<Throwable, Integer> countdown(int count) {
        if (count <= 0) {
            return Effect.succeed(0);
        }
        return Effect.succeed(count).flatMap(x -> 
            countdown(count - 1).map(y -> x + y)
        );
    }

    @Test
    void testDeepMapChain() throws Throwable {
        int depth = 100_000;
        
        Effect<Throwable, Integer> chain = Effect.succeed(0);
        for (int i = 0; i < depth; i++) {
            chain = chain.map(x -> x + 1);
        }

        Integer result = runtime.unsafeRun(chain);
        assertEquals(depth, result);
    }

    @Test
    void testDeepErrorPropagation() {
        int depth = 50_000;
        
        Effect<Throwable, Integer> tempChain = Effect.succeed(0);
        for (int i = 0; i < depth - 1; i++) {
            tempChain = tempChain.flatMap(x -> Effect.succeed(x + 1));
        }
        // Add error at the end
        final Effect<Throwable, Integer> chain = tempChain.flatMap(x -> Effect.fail(new RuntimeException("boom")));

        RuntimeException thrown = assertThrows(
            RuntimeException.class,
            () -> runtime.unsafeRun(chain)
        );
        assertEquals("boom", thrown.getMessage());
    }

    @Test
    void testDeepCatchAllChain() throws Throwable {
        int depth = 50_000;
        
        Effect<Throwable, Integer> chain = Effect.<Throwable, Integer>fail(
            new RuntimeException("error")
        );
        
        for (int i = 0; i < depth; i++) {
            final int value = i;
            chain = chain.catchAll(e -> Effect.succeed(value));
        }

        Integer result = runtime.unsafeRun(chain);
        assertEquals(0, result); // First catchAll handles it
    }

    @Test
    void testDeepFoldChain() throws Throwable {
        int depth = 10_000;
        AtomicInteger errorCount = new AtomicInteger(0);
        
        Effect<Throwable, Integer> chain = Effect.succeed(0);
        for (int i = 0; i < depth; i++) {
            final int value = i;
            chain = chain.flatMap(x -> {
                if (x == value) {
                    return Effect.<Throwable, Integer>fail(new RuntimeException("error"))
                        .catchAll(e -> {
                            errorCount.incrementAndGet();
                            return Effect.succeed(x + 1);
                        });
                }
                return Effect.succeed(x + 1);
            });
        }

        Integer result = runtime.unsafeRun(chain);
        assertTrue(result > 0);
        assertTrue(errorCount.get() > 0);
    }

    @Test
    void testDeepNestedFlatMaps() throws Throwable {
        int depth = 5_000;

        Effect<Throwable, Integer> nested = buildNested(depth);
        Integer result = runtime.unsafeRun(nested);
        assertTrue(result > 0);
    }

    private Effect<Throwable, Integer> buildNested(int n) {
        if (n <= 0) {
            return Effect.succeed(1);
        }
        return Effect.succeed(n)
            .flatMap(x -> buildNested(n - 1)
                .flatMap(y -> Effect.succeed(x + y)));
    }

    @Test
    void testDeepMapErrorChain() throws Throwable {
        int depth = 50_000;
        
        Effect<RuntimeException, Integer> chain = Effect.succeed(42);
        for (int i = 0; i < depth; i++) {
            chain = chain.mapError(e -> new RuntimeException("wrapped: " + e.getMessage()));
        }

        Integer result = runtime.unsafeRun(chain);
        assertEquals(42, result);
    }

    @Test
    void testDeepSuspendChain() throws Throwable {
        int depth = 50_000;
        
        Effect<Throwable, Integer> chain = Effect.succeed(0);
        for (int i = 0; i < depth; i++) {
            chain = chain.flatMap(x -> Effect.suspend(() -> x + 1));
        }

        Integer result = runtime.unsafeRun(chain);
        assertEquals(depth, result);
    }

    @Test
    void testDeepMixedOperations() throws Throwable {
        int depth = 50_000;
        
        Effect<Throwable, Integer> chain = Effect.succeed(0);
        for (int i = 0; i < depth; i++) {
            if (i % 3 == 0) {
                chain = chain.map(x -> x + 1);
            } else if (i % 3 == 1) {
                chain = chain.flatMap(x -> Effect.succeed(x + 1));
            } else {
                chain = chain.flatMap(x -> Effect.suspend(() -> x + 1));
            }
        }

        Integer result = runtime.unsafeRun(chain);
        assertEquals(depth, result);
    }

    @Test
    void testVeryDeepChain() throws Throwable {
        // This would definitely overflow the stack with recursive execution
        int depth = 1_000_000;
        
        Effect<Throwable, Long> chain = Effect.succeed(0L);
        for (int i = 0; i < depth; i++) {
            chain = chain.flatMap(x -> Effect.succeed(x + 1));
        }

        Long result = runtime.unsafeRun(chain);
        assertEquals(depth, result);
    }

    @Test
    void testDeepChainWithEarlyExit() throws Throwable {
        int depth = 50_000;
        int exitAt = 25_000;
        
        Effect<Throwable, Integer> tempChain = Effect.succeed(0);
        for (int i = 0; i < depth; i++) {
            final int current = i;
            tempChain = tempChain.flatMap(x -> {
                if (x == exitAt) {
                    return Effect.fail(new RuntimeException("exit"));
                }
                return Effect.succeed(x + 1);
            });
        }
        final Effect<Throwable, Integer> chain = tempChain;

        RuntimeException thrown = assertThrows(
            RuntimeException.class,
            () -> runtime.unsafeRun(chain)
        );
        assertEquals("exit", thrown.getMessage());
    }

    @Test
    void testDeepOrElseChain() throws Throwable {
        int depth = 50_000;
        
        Effect<Throwable, Integer> chain = Effect.fail(new RuntimeException("error"));
        for (int i = 0; i < depth; i++) {
            final int value = i;
            chain = chain.orElse(Effect.succeed(value));
        }

        Integer result = runtime.unsafeRun(chain);
        assertEquals(0, result); // First orElse succeeds
    }

    @Test
    void testDeepAttemptChain() throws Throwable {
        int depth = 10_000;
        
        Effect<Throwable, Integer> chain = Effect.succeed(0);
        for (int i = 0; i < depth; i++) {
            chain = chain.flatMap(x -> 
                Effect.succeed(x + 1).attempt()
                    .flatMap(either -> switch (either) {
                        case com.cajunsystems.roux.data.Either.Left<Throwable, Integer> left ->
                            Effect.fail(left.value());
                        case com.cajunsystems.roux.data.Either.Right<Throwable, Integer> right ->
                            Effect.succeed(right.value());
                    })
            );
        }

        Integer result = runtime.unsafeRun(chain);
        assertEquals(depth, result);
    }

    @Test
    void testTrampolinePreservesSemantics() throws Throwable {
        // Verify trampolined execution has same semantics as direct execution
        Effect<Throwable, String> program = Effect.succeed("hello")
            .flatMap(s -> Effect.succeed(s + " "))
            .flatMap(s -> Effect.succeed(s + "world"))
            .map(String::toUpperCase)
            .flatMap(s -> Effect.suspend(() -> s + "!"));

        String result = runtime.unsafeRun(program);
        assertEquals("HELLO WORLD!", result);
    }

    @Test
    void testDeepChainWithWidenAndNarrow() throws Throwable {
        int depth = 50_000;
        
        Effect<java.io.IOException, Integer> chain = Effect.succeed(0);
        for (int i = 0; i < depth; i++) {
            chain = chain.widen().narrow();
            chain = chain.map(x -> x + 1);
        }

        Integer result = runtime.unsafeRun(chain);
        assertEquals(depth, result);
    }

    @Test
    void testDeepErrorRecovery() throws Throwable {
        int depth = 10_000;
        
        Effect<Throwable, Integer> chain = Effect.succeed(0);
        for (int i = 0; i < depth; i++) {
            final int current = i;
            chain = chain.flatMap(x -> {
                if (current % 100 == 0) {
                    // Throw error every 100 iterations
                    return Effect.<Throwable, Integer>fail(new RuntimeException("error"))
                        .catchAll(e -> Effect.succeed(x + 1));
                }
                return Effect.succeed(x + 1);
            });
        }

        Integer result = runtime.unsafeRun(chain);
        assertEquals(depth, result);
    }
}
