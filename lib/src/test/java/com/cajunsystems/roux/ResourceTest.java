package com.cajunsystems.roux;

import com.cajunsystems.roux.data.Unit;
import com.cajunsystems.roux.runtime.DefaultEffectRuntime;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ResourceTest {

    private final EffectRuntime runtime = DefaultEffectRuntime.create();

    // -----------------------------------------------------------------------
    // Basic acquire / release
    // -----------------------------------------------------------------------

    @Test
    void use_acquiresAndReleasesResource() throws Throwable {
        AtomicBoolean acquired = new AtomicBoolean(false);
        AtomicBoolean released = new AtomicBoolean(false);

        Resource<String> resource = Resource.make(
                Effect.suspend(() -> {
                    acquired.set(true);
                    return "connection";
                }),
                r -> Effect.runnable(() -> released.set(true))
        );

        String result = runtime.unsafeRun(resource.use(r -> Effect.succeed(r + "-used")));

        assertTrue(acquired.get(), "resource should have been acquired");
        assertTrue(released.get(), "resource should have been released");
        assertEquals("connection-used", result);
    }

    @Test
    void use_releasesOnFailure() {
        AtomicBoolean released = new AtomicBoolean(false);

        Resource<String> resource = Resource.make(
                Effect.succeed("resource"),
                r -> Effect.runnable(() -> released.set(true))
        );

        assertThrows(RuntimeException.class, () ->
                runtime.unsafeRun(resource.use(r ->
                        Effect.<Throwable, String>fail(new RuntimeException("use failed"))
                ))
        );

        assertTrue(released.get(), "resource should be released even when use fails");
    }

    @Test
    void use_releaseRunsExactlyOnce_onSuccess() throws Throwable {
        AtomicInteger releaseCount = new AtomicInteger(0);

        Resource<String> resource = Resource.make(
                Effect.succeed("resource"),
                r -> Effect.runnable(releaseCount::incrementAndGet)
        );

        runtime.unsafeRun(resource.use(r -> Effect.succeed("ok")));

        assertEquals(1, releaseCount.get(), "release should run exactly once");
    }

    @Test
    void use_releaseRunsExactlyOnce_onFailure() {
        AtomicInteger releaseCount = new AtomicInteger(0);

        Resource<String> resource = Resource.make(
                Effect.succeed("resource"),
                r -> Effect.runnable(releaseCount::incrementAndGet)
        );

        assertThrows(RuntimeException.class, () ->
                runtime.unsafeRun(resource.use(r ->
                        Effect.<Throwable, String>fail(new RuntimeException("fail"))
                ))
        );

        assertEquals(1, releaseCount.get(), "release should run exactly once on failure");
    }

    // -----------------------------------------------------------------------
    // Original error is preserved when release fails
    // -----------------------------------------------------------------------

    @Test
    void use_originalErrorPreserved_whenReleaseFails() {
        Resource<String> resource = Resource.make(
                Effect.succeed("resource"),
                r -> Effect.suspend(() -> {
                    throw new RuntimeException("release failed");
                })
        );

        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                runtime.unsafeRun(resource.use(r ->
                        Effect.<Throwable, String>fail(new RuntimeException("use failed"))
                ))
        );

        // Original use-error is preserved; release-error is suppressed
        assertEquals("use failed", thrown.getMessage());
    }

    // -----------------------------------------------------------------------
    // fromCloseable
    // -----------------------------------------------------------------------

    @Test
    void fromCloseable_closesAutomatically() throws Throwable {
        AtomicBoolean closed = new AtomicBoolean(false);

        AutoCloseable closeable = () -> closed.set(true);

        Resource<AutoCloseable> resource = Resource.fromCloseable(
                Effect.succeed(closeable)
        );

        runtime.unsafeRun(resource.use(r -> Effect.succeed("done")));

        assertTrue(closed.get(), "AutoCloseable.close() should have been called");
    }

    @Test
    void fromCloseable_closesOnFailure() {
        AtomicBoolean closed = new AtomicBoolean(false);
        AutoCloseable closeable = () -> closed.set(true);

        Resource<AutoCloseable> resource = Resource.fromCloseable(
                Effect.succeed(closeable)
        );

        assertThrows(RuntimeException.class, () ->
                runtime.unsafeRun(resource.use(r ->
                        Effect.<Throwable, String>fail(new RuntimeException("oops"))
                ))
        );

        assertTrue(closed.get(), "AutoCloseable should be closed even on failure");
    }

    // -----------------------------------------------------------------------
    // Acquire failure
    // -----------------------------------------------------------------------

    @Test
    void use_propagatesAcquireFailure() {
        AtomicBoolean releaseInvoked = new AtomicBoolean(false);

        Resource<String> resource = Resource.make(
                Effect.<Throwable, String>fail(new RuntimeException("cannot acquire")),
                r -> Effect.runnable(() -> releaseInvoked.set(true))
        );

        assertThrows(RuntimeException.class, () ->
                runtime.unsafeRun(resource.use(r -> Effect.succeed("unreachable")))
        );

        assertFalse(releaseInvoked.get(), "release must NOT run if acquire failed");
    }

    // -----------------------------------------------------------------------
    // Nesting resources
    // -----------------------------------------------------------------------

    @Test
    void nestedUse_bothResourcesReleased() throws Throwable {
        AtomicBoolean outerReleased = new AtomicBoolean(false);
        AtomicBoolean innerReleased = new AtomicBoolean(false);

        Resource<String> outer = Resource.make(
                Effect.succeed("outer"),
                r -> Effect.runnable(() -> outerReleased.set(true))
        );

        Resource<String> inner = Resource.make(
                Effect.succeed("inner"),
                r -> Effect.runnable(() -> innerReleased.set(true))
        );

        String result = runtime.unsafeRun(
                outer.use(o ->
                        inner.use(i -> Effect.succeed(o + "+" + i))
                )
        );

        assertEquals("outer+inner", result);
        assertTrue(outerReleased.get(), "outer resource should be released");
        assertTrue(innerReleased.get(), "inner resource should be released");
    }

    @Test
    void nestedUse_bothReleasedWhenInnerFails() {
        AtomicBoolean outerReleased = new AtomicBoolean(false);
        AtomicBoolean innerReleased = new AtomicBoolean(false);

        Resource<String> outer = Resource.make(
                Effect.succeed("outer"),
                r -> Effect.runnable(() -> outerReleased.set(true))
        );

        Resource<String> inner = Resource.make(
                Effect.succeed("inner"),
                r -> Effect.runnable(() -> innerReleased.set(true))
        );

        assertThrows(RuntimeException.class, () ->
                runtime.unsafeRun(
                        outer.use(o ->
                                inner.use(i ->
                                        Effect.<Throwable, String>fail(new RuntimeException("boom"))
                                )
                        )
                )
        );

        assertTrue(outerReleased.get(), "outer resource should be released even after inner failure");
        assertTrue(innerReleased.get(), "inner resource should be released");
    }

    // -----------------------------------------------------------------------
    // Resource.ensuring (static helper)
    // -----------------------------------------------------------------------

    @Test
    void ensuring_finalizerRunsOnSuccess() throws Throwable {
        AtomicBoolean finalizerRan = new AtomicBoolean(false);

        Effect<Throwable, String> effect = Resource.ensuring(
                Effect.succeed("hello"),
                Effect.runnable(() -> finalizerRan.set(true))
        );

        String result = runtime.unsafeRun(effect);
        assertEquals("hello", result);
        assertTrue(finalizerRan.get(), "finalizer should run on success");
    }

    @Test
    void ensuring_finalizerRunsOnFailure() {
        AtomicBoolean finalizerRan = new AtomicBoolean(false);

        Effect<Throwable, String> effect = Resource.ensuring(
                Effect.<Throwable, String>fail(new RuntimeException("boom")),
                Effect.runnable(() -> finalizerRan.set(true))
        );

        assertThrows(RuntimeException.class, () -> runtime.unsafeRun(effect));
        assertTrue(finalizerRan.get(), "finalizer should run on failure");
    }

    @Test
    void ensuring_originalErrorPreserved_whenFinalizerFails() {
        Effect<Throwable, String> effect = Resource.ensuring(
                Effect.<Throwable, String>fail(new RuntimeException("original")),
                Effect.runnable(() -> { throw new RuntimeException("finalizer failed"); })
        );

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> runtime.unsafeRun(effect));
        assertEquals("original", thrown.getMessage());
    }

    @Test
    void ensuring_successPreserved_whenFinalizerFails() {
        // When use succeeds but finalizer errors are suppressed
        Effect<Throwable, String> effect = Resource.ensuring(
                Effect.<Throwable, String>succeed("result"),
                Effect.runnable(() -> { throw new RuntimeException("finalizer blew up"); })
        );

        // Finalizer errors on success path are suppressed per our semantics
        assertDoesNotThrow(() -> {
            String result = runtime.unsafeRun(effect);
            assertEquals("result", result);
        });
    }

    // -----------------------------------------------------------------------
    // Composing with other combinators
    // -----------------------------------------------------------------------

    @Test
    void use_composesWithMap() throws Throwable {
        Resource<Integer> resource = Resource.make(
                Effect.succeed(10),
                r -> Effect.unit()
        );

        String result = runtime.unsafeRun(
                resource.use(n -> Effect.succeed(n * 2)).map(Object::toString)
        );

        assertEquals("20", result);
    }

    @Test
    void use_composesWithRetry() throws Throwable {
        AtomicInteger attempts = new AtomicInteger(0);
        AtomicInteger releaseCount = new AtomicInteger(0);

        // Note: retry creates a new resource use per attempt
        Effect<Throwable, String> effect = Resource.make(
                Effect.succeed("conn"),
                r -> Effect.runnable(releaseCount::incrementAndGet)
        ).use(r -> Effect.suspend(() -> {
            if (attempts.incrementAndGet() < 3) throw new RuntimeException("not yet");
            return "done";
        })).retry(RetryPolicy.immediate().maxAttempts(5));

        String result = runtime.unsafeRun(effect);
        assertEquals("done", result);
        assertEquals(3, attempts.get());
        // Each attempt acquires and releases; 3 attempts = 3 releases
        assertEquals(3, releaseCount.get());
    }

    @Test
    void use_withSpecificErrorType() throws Throwable {
        Resource<String> resource = Resource.make(
                Effect.<IOException, String>suspend(() -> "resource"),
                r -> Effect.unit()
        );

        String result = runtime.unsafeRun(resource.use(r -> Effect.succeed(r.toUpperCase())));
        assertEquals("RESOURCE", result);
    }

    // -----------------------------------------------------------------------
    // Resource value is accessible during release
    // -----------------------------------------------------------------------

    @Test
    void release_receivesAcquiredValue() throws Throwable {
        AtomicReference<String> releasedValue = new AtomicReference<>();

        Resource<String> resource = Resource.make(
                Effect.succeed("my-connection-42"),
                r -> Effect.runnable(() -> releasedValue.set(r))
        );

        runtime.unsafeRun(resource.use(r -> Effect.succeed("done")));

        assertEquals("my-connection-42", releasedValue.get());
    }
}
