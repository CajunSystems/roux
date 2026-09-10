package com.cajunsystems.roux;

import com.cajunsystems.roux.runtime.DefaultEffectRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class JavaErgonomicsTest {

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
    // Effect.blocking / blockingUnit
    // -----------------------------------------------------------------------

    @Test
    void blocking_wrapsLegacyCall() throws Throwable {
        Effect<IOException, String> effect = Effect.blocking(() -> "legacy-result");
        assertEquals("legacy-result", runtime.unsafeRun(effect));
    }

    @Test
    void blocking_propagatesException() {
        Effect<IOException, String> effect = Effect.blocking(() -> {
            throw new IOException("disk error");
        });
        assertThrows(IOException.class, () -> runtime.unsafeRun(effect));
    }

    @Test
    void blockingUnit_runsSideEffect() throws Throwable {
        AtomicBoolean ran = new AtomicBoolean(false);
        Effect<RuntimeException, ?> effect = Effect.blockingUnit(() -> ran.set(true));
        runtime.unsafeRun(effect);
        assertTrue(ran.get());
    }

    // -----------------------------------------------------------------------
    // Effect.fromCallable
    // -----------------------------------------------------------------------

    @Test
    void fromCallable_liftsCallable() throws Exception {
        Effect<Exception, Integer> effect = Effect.fromCallable(() -> 42);
        assertEquals(42, runtime.unsafeRun(effect));
    }

    @Test
    void fromCallable_propagatesCheckedException() {
        Effect<Exception, Integer> effect = Effect.fromCallable(() -> {
            throw new IOException("callable failed");
        });
        assertThrows(IOException.class, () -> runtime.unsafeRun(effect));
    }

    // -----------------------------------------------------------------------
    // Effect.fromFuture
    // -----------------------------------------------------------------------

    @Test
    void fromFuture_eagerVariant_resolvesValue() throws Throwable {
        CompletableFuture<String> cf = CompletableFuture.completedFuture("done");
        Effect<Throwable, String> effect = Effect.fromFuture(cf);
        assertEquals("done", runtime.unsafeRun(effect));
    }

    @Test
    void fromFuture_lazyVariant_defersFutureCreation() throws Throwable {
        AtomicBoolean created = new AtomicBoolean(false);
        Effect<Throwable, String> effect = Effect.fromFuture(() -> {
            created.set(true);
            return CompletableFuture.completedFuture("lazy");
        });
        assertFalse(created.get(), "factory should not run before effect executes");
        assertEquals("lazy", runtime.unsafeRun(effect));
        assertTrue(created.get());
    }

    @Test
    void fromFuture_propagatesFailure() {
        CompletableFuture<String> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IOException("future failed"));
        Effect<Throwable, String> effect = Effect.fromFuture(failed);
        assertThrows(IOException.class, () -> runtime.unsafeRun(effect));
    }

    // -----------------------------------------------------------------------
    // Effect.peek / recover / recoverWith
    // -----------------------------------------------------------------------

    @Test
    void peek_runsActionAndPassesValueThrough() throws Throwable {
        AtomicBoolean saw = new AtomicBoolean(false);
        Effect<RuntimeException, Integer> effect = Effect.<RuntimeException, Integer>succeed(7)
                .peek(v -> saw.set(v == 7));
        assertEquals(7, runtime.unsafeRun(effect));
        assertTrue(saw.get());
    }

    @Test
    void recover_returnsFallbackValueOnError() throws Throwable {
        Effect<IOException, String> effect = Effect.<IOException, String>fail(new IOException("oops"))
                .recover(e -> "fallback");
        assertEquals("fallback", runtime.unsafeRun(effect));
    }

    @Test
    void recover_passesSuccessThroughUnchanged() throws Throwable {
        Effect<IOException, String> effect = Effect.<IOException, String>succeed("ok")
                .recover(e -> "fallback");
        assertEquals("ok", runtime.unsafeRun(effect));
    }

    @Test
    void recoverWith_switchesToAlternativeEffectOnError() throws Throwable {
        Effect<IOException, String> effect = Effect.<IOException, String>fail(new IOException("primary failed"))
                .recoverWith(e -> Effect.succeed("from-cache"));
        assertEquals("from-cache", runtime.unsafeRun(effect));
    }

    // -----------------------------------------------------------------------
    // EffectRuntime.toFuture
    // -----------------------------------------------------------------------

    @Test
    void toFuture_completesWithResult() throws Exception {
        CompletableFuture<String> future = runtime.toFuture(Effect.succeed("hello"));
        assertEquals("hello", future.get());
    }

    @Test
    void toFuture_completesExceptionallyOnFailure() {
        CompletableFuture<String> future = runtime.toFuture(
                Effect.<IOException, String>fail(new IOException("boom"))
        );
        var ex = assertThrows(java.util.concurrent.ExecutionException.class, future::get);
        assertInstanceOf(IOException.class, ex.getCause());
    }

    // -----------------------------------------------------------------------
    // Effects.fromExecutor
    // -----------------------------------------------------------------------

    @Test
    void fromExecutor_runsCallableOnExecutorAndReturnsResult() throws Throwable {
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            Effect<Throwable, Integer> effect = Effects.fromExecutor(pool, () -> 99);
            assertEquals(99, runtime.unsafeRun(effect));
        } finally {
            pool.shutdown();
        }
    }

    // -----------------------------------------------------------------------
    // Resource.map
    // -----------------------------------------------------------------------

    @Test
    void resourceMap_transformsValuePreservingRelease() throws Throwable {
        AtomicBoolean released = new AtomicBoolean(false);
        Resource<String> base = Resource.make(
                Effect.succeed("raw"),
                __ -> Effect.runnable(() -> released.set(true))
        );
        Resource<Integer> mapped = base.map(String::length);
        Integer result = runtime.unsafeRun(mapped.use(Effect::succeed));
        assertEquals(3, result);
        assertTrue(released.get(), "release should have run after use");
    }

    // -----------------------------------------------------------------------
    // Resources fluent builder
    // -----------------------------------------------------------------------

    @Test
    void resources_with_and_use_tworesources() throws Throwable {
        AtomicBoolean rel1 = new AtomicBoolean(false);
        AtomicBoolean rel2 = new AtomicBoolean(false);

        Resource<String> r1 = Resource.make(
                Effect.succeed("A"),
                __ -> Effect.runnable(() -> rel1.set(true))
        );
        Resource<String> r2 = Resource.make(
                Effect.succeed("B"),
                __ -> Effect.runnable(() -> rel2.set(true))
        );

        String result = runtime.unsafeRun(
                Resources.with(r1)
                         .and(r2)
                         .use((a, b) -> Effect.succeed(a + b))
        );

        assertEquals("AB", result);
        assertTrue(rel1.get(), "r1 should be released");
        assertTrue(rel2.get(), "r2 should be released");
    }

    @Test
    void resources_with_and_and_use_threeresources() throws Throwable {
        Resource<Integer> r1 = Resource.make(Effect.succeed(1), __ -> Effect.unit());
        Resource<Integer> r2 = Resource.make(Effect.succeed(2), __ -> Effect.unit());
        Resource<Integer> r3 = Resource.make(Effect.succeed(3), __ -> Effect.unit());

        int result = runtime.unsafeRun(
                Resources.with(r1)
                         .and(r2)
                         .and(r3)
                         .use((a, b, c) -> Effect.succeed(a + b + c))
        );

        assertEquals(6, result);
    }

    @Test
    void resources_releases_in_reverse_order_on_failure() {
        List<String> releaseOrder = new java.util.ArrayList<>();

        Resource<String> r1 = Resource.make(
                Effect.succeed("r1"),
                __ -> Effect.runnable(() -> releaseOrder.add("rel-r1"))
        );
        Resource<String> r2 = Resource.make(
                Effect.succeed("r2"),
                __ -> Effect.runnable(() -> releaseOrder.add("rel-r2"))
        );

        assertThrows(IOException.class, () ->
                runtime.unsafeRun(
                        Resources.with(r1)
                                 .and(r2)
                                 .use((a, b) -> Effect.fail(new IOException("use failed")))
                )
        );

        // r2 acquired second, so released first
        assertEquals(List.of("rel-r2", "rel-r1"), releaseOrder);
    }
}
