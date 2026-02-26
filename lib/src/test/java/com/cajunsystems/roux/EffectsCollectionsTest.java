package com.cajunsystems.roux;

import com.cajunsystems.roux.runtime.DefaultEffectRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.cajunsystems.roux.Effects.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Effects.sequence, Effects.traverse, Effects.parAll, and Effects.race
 * added in 0.2.0.
 */
class EffectsCollectionsTest {

    private DefaultEffectRuntime runtime;

    @BeforeEach
    void setUp() {
        runtime = DefaultEffectRuntime.create();
    }

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    // -----------------------------------------------------------------------
    // sequence
    // -----------------------------------------------------------------------

    @Test
    void sequenceRunsAllEffectsInOrder() throws Throwable {
        AtomicInteger counter = new AtomicInteger(0);

        List<Effect<Throwable, Integer>> effects = List.of(
                Effect.suspend(() -> { counter.incrementAndGet(); return 1; }),
                Effect.suspend(() -> { counter.incrementAndGet(); return 2; }),
                Effect.suspend(() -> { counter.incrementAndGet(); return 3; })
        );

        List<Integer> results = runtime.unsafeRun(sequence(effects));

        assertEquals(List.of(1, 2, 3), results);
        assertEquals(3, counter.get());
    }

    @Test
    void sequenceShortCircuitsOnFirstFailure() {
        AtomicInteger ran = new AtomicInteger(0);

        List<Effect<Throwable, Integer>> effects = List.of(
                Effect.suspend(() -> { ran.incrementAndGet(); return 1; }),
                Effect.fail(new RuntimeException("stop")),
                Effect.suspend(() -> { ran.incrementAndGet(); return 3; })
        );

        assertThrows(RuntimeException.class, () -> runtime.unsafeRun(sequence(effects)));
        assertEquals(1, ran.get()); // third effect should never run
    }

    @Test
    void sequenceEmptyListReturnsEmptyList() throws Throwable {
        List<Integer> results = runtime.unsafeRun(sequence(List.of()));
        assertTrue(results.isEmpty());
    }

    // -----------------------------------------------------------------------
    // traverse
    // -----------------------------------------------------------------------

    @Test
    void traverseAppliesFunctionToEachElement() throws Throwable {
        List<Integer> results = runtime.unsafeRun(
                traverse(List.of(1, 2, 3), n -> Effect.succeed(n * 10))
        );

        assertEquals(List.of(10, 20, 30), results);
    }

    @Test
    void traverseShortCircuitsOnFailure() {
        AtomicInteger mapped = new AtomicInteger(0);

        assertThrows(RuntimeException.class, () ->
                runtime.unsafeRun(traverse(
                        List.of(1, 2, 3),
                        n -> {
                            if (n == 2) return Effect.fail(new RuntimeException("fail at 2"));
                            mapped.incrementAndGet();
                            return Effect.succeed(n);
                        }
                ))
        );
        // Only the first element should have been processed successfully
        assertEquals(1, mapped.get());
    }

    @Test
    void traverseEmptyListReturnsEmptyList() throws Throwable {
        List<String> results = runtime.unsafeRun(
                traverse(List.<Integer>of(), n -> Effect.succeed(n.toString()))
        );
        assertTrue(results.isEmpty());
    }

    // -----------------------------------------------------------------------
    // parAll
    // -----------------------------------------------------------------------

    @Test
    void parAllRunsEffectsInParallelAndCollectsResults() throws Throwable {
        List<Effect<Throwable, Integer>> effects = List.of(
                Effect.suspend(() -> { Thread.sleep(50); return 1; }),
                Effect.suspend(() -> { Thread.sleep(50); return 2; }),
                Effect.suspend(() -> { Thread.sleep(50); return 3; })
        );

        long start = System.currentTimeMillis();
        List<Integer> results = runtime.unsafeRun(parAll(effects));
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(List.of(1, 2, 3), results);
        // All 3 run in parallel so should finish in ~50ms, not ~150ms
        assertTrue(elapsed < 300, "Expected parallel execution, took " + elapsed + "ms");
    }

    @Test
    void parAllEmptyListReturnsEmptyList() throws Throwable {
        List<Integer> results = runtime.unsafeRun(parAll(List.of()));
        assertTrue(results.isEmpty());
    }

    // -----------------------------------------------------------------------
    // race
    // -----------------------------------------------------------------------

    @Test
    void raceReturnsFastestEffect() throws Throwable {
        Effect<Throwable, String> slow = Effect.suspend(() -> {
            Thread.sleep(500);
            return "slow";
        });
        Effect<Throwable, String> fast = Effect.suspend(() -> {
            Thread.sleep(10);
            return "fast";
        });

        String result = runtime.unsafeRun(race(slow, fast));
        assertEquals("fast", result);
    }

    @Test
    void raceWithListReturnsFastest() throws Throwable {
        List<Effect<Throwable, Integer>> effects = List.of(
                Effect.suspend(() -> { Thread.sleep(200); return 1; }),
                Effect.suspend(() -> { Thread.sleep(10);  return 2; }),
                Effect.suspend(() -> { Thread.sleep(300); return 3; })
        );

        Integer result = runtime.unsafeRun(race(effects));
        assertEquals(2, result);
    }

    @Test
    void racePropagatesErrorIfAllFail() throws Throwable {
        // Race returns whatever completes first — even a failure
        Effect<Throwable, String> always1 = Effect.suspend(() -> {
            Thread.sleep(20);
            throw new RuntimeException("e1");
        });
        Effect<Throwable, String> always2 = Effect.suspend(() -> {
            Thread.sleep(100);
            throw new RuntimeException("e2");
        });

        // The first to complete is the one that fails first
        assertThrows(RuntimeException.class, () -> runtime.unsafeRun(race(always1, always2)));
    }

    @Test
    void raceWithSingleElementReturnsThatEffect() throws Throwable {
        String result = runtime.unsafeRun(race(List.of(Effect.succeed("only"))));
        assertEquals("only", result);
    }
}
