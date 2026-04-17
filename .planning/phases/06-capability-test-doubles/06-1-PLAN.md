# Plan: 06-1 — Capability Test Doubles

## Objective
Build `TestCapabilityHandler` — a stub-based `CapabilityHandler` that records every invocation and
provides a fluent verification API (`verify(Type.class).calledOnce()`, `calledWith(predicate)`, etc.).
Reduces boilerplate for capability-based tests and replaces hand-crafted mock lambdas.

## Context

**How capabilities work:**
- `Capability<R>` — a data description of an operation
- `CapabilityHandler<C>` — interprets capabilities via `<R> R handle(C capability)`
- `unsafeRunWithHandler(effect, handler)` — runs an effect with a handler attached
- `CapabilityHandler.Builder` already provides type-safe dispatch via `on(Class, handler)` — `TestCapabilityHandler` mirrors this but adds recording

**Design:**
- `TestCapabilityHandler implements CapabilityHandler<Capability<?>>` — drop-in for any handler slot
- Stubs stored in `Map<Class<?>, ThrowingFunction<?, ?>>` — exact-match dispatch (like `Builder.build()`)
- Calls stored in `Map<Class<?>, List<Object>>` using `ConcurrentHashMap` + `CopyOnWriteArrayList` — thread-safe
- `verify(Class<C>)` returns `CapabilityVerifier<C>` with fluent assertions on recorded calls
- `CapabilityVerifier` uses JUnit assertions internally (fine — test scope only)

**Packages:**
- `com.cajunsystems.roux.testing` (test scope only — no production changes)

**Key existing classes:**
- `com.cajunsystems.roux.data.ThrowingFunction` — already used by `CapabilityHandler.Builder`, reuse here
- `com.cajunsystems.roux.testing.TestRuntime` — use in tests to run effects with the handler
- `com.cajunsystems.roux.testing.EffectAssertions.assertThat(effect, handler)` — use for single-effect tests

## Tasks

### Task 1 — Create `CapabilityVerifier`

Create `lib/src/test/java/com/cajunsystems/roux/testing/CapabilityVerifier.java`:

```java
package com.cajunsystems.roux.testing;

import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fluent verification API for recorded capability invocations.
 *
 * <p>Returned by {@link TestCapabilityHandler#verify(Class)}. All methods
 * return {@code this} for chaining.
 *
 * <pre>{@code
 * handler.verify(FetchUser.class)
 *     .calledOnce()
 *     .calledWith(req -> req.userId().equals("alice"));
 * }</pre>
 */
public final class CapabilityVerifier<C> {

    private final String typeName;
    private final List<C> calls;

    CapabilityVerifier(String typeName, List<C> calls) {
        this.typeName = typeName;
        this.calls = List.copyOf(calls);
    }

    /** Assert this capability was called exactly once. */
    public CapabilityVerifier<C> calledOnce() {
        assertEquals(1, calls.size(),
                typeName + " expected to be called once but was called " + calls.size() + " time(s)");
        return this;
    }

    /** Assert this capability was never called. */
    public CapabilityVerifier<C> neverCalled() {
        assertEquals(0, calls.size(),
                typeName + " expected to never be called but was called " + calls.size() + " time(s)");
        return this;
    }

    /** Assert this capability was called exactly {@code expected} times. */
    public CapabilityVerifier<C> calledTimes(int expected) {
        assertEquals(expected, calls.size(),
                typeName + " expected to be called " + expected + " time(s) but was called " + calls.size() + " time(s)");
        return this;
    }

    /**
     * Assert that at least one invocation matched the given predicate.
     * Useful for checking argument values.
     */
    public CapabilityVerifier<C> calledWith(Predicate<C> predicate) {
        assertTrue(calls.stream().anyMatch(predicate),
                typeName + " was not called with a matching argument. Total calls: " + calls.size());
        return this;
    }
}
```

### Task 2 — Create `TestCapabilityHandler`

Create `lib/src/test/java/com/cajunsystems/roux/testing/TestCapabilityHandler.java`:

```java
package com.cajunsystems.roux.testing;

import com.cajunsystems.roux.capability.Capability;
import com.cajunsystems.roux.capability.CapabilityHandler;
import com.cajunsystems.roux.data.ThrowingFunction;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A test-friendly {@link CapabilityHandler} that records every invocation
 * and provides a fluent verification API.
 *
 * <p>Usage:
 * <pre>{@code
 * TestCapabilityHandler handler = TestCapabilityHandler.builder()
 *     .stub(FetchUser.class, req -> new User(req.userId(), "Alice"))
 *     .stub(DeleteUser.class, req -> true)
 *     .build();
 *
 * runtime.unsafeRunWithHandler(effect, handler);
 *
 * handler.verify(FetchUser.class).calledOnce();
 * handler.verify(DeleteUser.class).calledWith(req -> req.userId().equals("u1"));
 * handler.verify(SendEmail.class).neverCalled();
 * }</pre>
 *
 * <p>Stubs dispatch on exact capability class (same semantics as
 * {@link CapabilityHandler#builder()}). Unregistered capabilities throw
 * {@link UnsupportedOperationException}.
 */
public final class TestCapabilityHandler implements CapabilityHandler<Capability<?>> {

    private final Map<Class<?>, ThrowingFunction<?, ?>> stubs;
    private final Map<Class<?>, List<Object>> calls = new ConcurrentHashMap<>();

    private TestCapabilityHandler(Map<Class<?>, ThrowingFunction<?, ?>> stubs) {
        this.stubs = Map.copyOf(stubs);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <R> R handle(Capability<?> capability) throws Exception {
        Class<?> type = capability.getClass();
        ThrowingFunction<Object, R> stub = (ThrowingFunction<Object, R>) stubs.get(type);
        if (stub == null) {
            throw new UnsupportedOperationException(
                    "No stub registered for capability: " + type.getName());
        }
        calls.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(capability);
        return stub.apply(capability);
    }

    /**
     * Return a {@link CapabilityVerifier} for all recorded invocations of
     * the given capability type. Works even if no calls were recorded
     * (returns a verifier with an empty call list).
     */
    @SuppressWarnings("unchecked")
    public <C extends Capability<?>> CapabilityVerifier<C> verify(Class<C> type) {
        List<C> recorded = (List<C>) (List<?>) calls.getOrDefault(type, List.of());
        return new CapabilityVerifier<>(type.getSimpleName(), recorded);
    }

    /** Return a fluent builder for constructing a {@code TestCapabilityHandler}. */
    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for {@link TestCapabilityHandler}. */
    public static final class Builder {

        private final Map<Class<?>, ThrowingFunction<?, ?>> stubs = new HashMap<>();

        private Builder() {}

        /**
         * Register a stub for a specific capability type.
         *
         * @param type    exact capability class (not a supertype)
         * @param handler stub lambda returning the canned response
         */
        public <C extends Capability<R>, R> Builder stub(
                Class<C> type,
                ThrowingFunction<C, R> handler
        ) {
            stubs.put(type, handler);
            return this;
        }

        /** Build the {@link TestCapabilityHandler}. */
        public TestCapabilityHandler build() {
            return new TestCapabilityHandler(stubs);
        }
    }
}
```

**After Task 2, compile check:**
```bash
cd /Users/pradeep.samuel/roux && ./gradlew :lib:compileTestJava 2>&1 | tail -20
```

**After Task 2, commit:**
```
feat(06-1): add TestCapabilityHandler and CapabilityVerifier
```
(Stage both files individually.)

### Task 3 — Create `TestCapabilityHandlerTest`

Create `lib/src/test/java/com/cajunsystems/roux/testing/TestCapabilityHandlerTest.java`:

```java
package com.cajunsystems.roux.testing;

import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.capability.Capability;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.cajunsystems.roux.testing.EffectAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class TestCapabilityHandlerTest {

    // Test capability domain
    sealed interface UserCap<R> extends Capability<R> {
        record FetchUser(String userId) implements UserCap<String> {}
        record DeleteUser(String userId) implements UserCap<Boolean> {}
    }

    private TestRuntime runtime;

    @BeforeEach
    void setUp() {
        runtime = TestRuntime.create();
    }

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    // -----------------------------------------------------------------------
    // Stub basics
    // -----------------------------------------------------------------------

    @Test
    void stub_returnsConfiguredValue() {
        TestCapabilityHandler handler = TestCapabilityHandler.builder()
                .stub(UserCap.FetchUser.class, req -> "Alice")
                .build();

        assertThat(new UserCap.FetchUser("u1").toEffect(), handler)
                .succeedsWith("Alice");
    }

    @Test
    void stub_canReadCapabilityFields() {
        TestCapabilityHandler handler = TestCapabilityHandler.builder()
                .stub(UserCap.FetchUser.class, req -> "Hello, " + req.userId())
                .build();

        assertThat(new UserCap.FetchUser("alice").toEffect(), handler)
                .succeedsWith("Hello, alice");
    }

    @Test
    void stub_unregisteredCapability_fails() {
        TestCapabilityHandler handler = TestCapabilityHandler.builder().build();

        // No stub for FetchUser — should fail
        assertThat(new UserCap.FetchUser("u1").toEffect(), handler).fails();
    }

    // -----------------------------------------------------------------------
    // neverCalled
    // -----------------------------------------------------------------------

    @Test
    void verify_neverCalled_passesWhenNoCalls() {
        TestCapabilityHandler handler = TestCapabilityHandler.builder()
                .stub(UserCap.FetchUser.class, req -> "Alice")
                .build();

        // No effects run — FetchUser was never invoked
        handler.verify(UserCap.FetchUser.class).neverCalled();
    }

    @Test
    void verify_neverCalled_failsWhenCalled() throws Throwable {
        TestCapabilityHandler handler = TestCapabilityHandler.builder()
                .stub(UserCap.FetchUser.class, req -> "Alice")
                .build();

        runtime.unsafeRunWithHandler(new UserCap.FetchUser("u1").toEffect(), handler);

        assertThrows(AssertionError.class, () ->
                handler.verify(UserCap.FetchUser.class).neverCalled());
    }

    // -----------------------------------------------------------------------
    // calledOnce / calledTimes
    // -----------------------------------------------------------------------

    @Test
    void verify_calledOnce_passesAfterOneCall() throws Throwable {
        TestCapabilityHandler handler = TestCapabilityHandler.builder()
                .stub(UserCap.FetchUser.class, req -> "Alice")
                .build();

        runtime.unsafeRunWithHandler(new UserCap.FetchUser("u1").toEffect(), handler);

        handler.verify(UserCap.FetchUser.class).calledOnce();
    }

    @Test
    void verify_calledTimes_tracksMultipleCalls() throws Throwable {
        TestCapabilityHandler handler = TestCapabilityHandler.builder()
                .stub(UserCap.FetchUser.class, req -> "Alice-" + req.userId())
                .build();

        Effect<Throwable, String> effect = new UserCap.FetchUser("u1").<Throwable>toEffect()
                .flatMap(__ -> new UserCap.FetchUser("u2").toEffect());
        runtime.unsafeRunWithHandler(effect, handler);

        handler.verify(UserCap.FetchUser.class).calledTimes(2);
    }

    // -----------------------------------------------------------------------
    // calledWith
    // -----------------------------------------------------------------------

    @Test
    void verify_calledWith_matchesArgument() throws Throwable {
        TestCapabilityHandler handler = TestCapabilityHandler.builder()
                .stub(UserCap.FetchUser.class, req -> req.userId().toUpperCase())
                .build();

        runtime.unsafeRunWithHandler(new UserCap.FetchUser("alice").toEffect(), handler);

        handler.verify(UserCap.FetchUser.class)
                .calledOnce()
                .calledWith(req -> req.userId().equals("alice"));
    }

    @Test
    void verify_calledWith_failsWhenNoMatchingArgument() throws Throwable {
        TestCapabilityHandler handler = TestCapabilityHandler.builder()
                .stub(UserCap.FetchUser.class, req -> "Alice")
                .build();

        runtime.unsafeRunWithHandler(new UserCap.FetchUser("alice").toEffect(), handler);

        assertThrows(AssertionError.class, () ->
                handler.verify(UserCap.FetchUser.class)
                        .calledWith(req -> req.userId().equals("bob")));
    }

    // -----------------------------------------------------------------------
    // End-to-end: multiple capabilities, stubs + verification
    // -----------------------------------------------------------------------

    @Test
    void endToEnd_multipleCapabilities_stubsAndVerifiesCorrectly() throws Throwable {
        TestCapabilityHandler handler = TestCapabilityHandler.builder()
                .stub(UserCap.FetchUser.class, req -> "Alice-" + req.userId())
                .stub(UserCap.DeleteUser.class, req -> true)
                .build();

        Effect<Throwable, Boolean> effect = new UserCap.FetchUser("u1").<Throwable>toEffect()
                .flatMap(__ -> new UserCap.DeleteUser("u1").toEffect());

        Boolean result = runtime.unsafeRunWithHandler(effect, handler);

        assertTrue(result);
        handler.verify(UserCap.FetchUser.class)
                .calledOnce()
                .calledWith(req -> req.userId().equals("u1"));
        handler.verify(UserCap.DeleteUser.class)
                .calledOnce()
                .calledWith(req -> req.userId().equals("u1"));
    }
}
```

**After Task 3, commit:**
```
test(06-1): add TestCapabilityHandlerTest with 10 tests
```

### Task 4 — Run all tests

```bash
cd /Users/pradeep.samuel/roux
./gradlew :lib:test --tests "com.cajunsystems.roux.testing.TestCapabilityHandlerTest" 2>&1 | tail -30
```

Then run the full test suite to ensure no regression:

```bash
./gradlew :lib:test 2>&1 | tail -20
```

All tests must pass.

## Verification

- [ ] `CapabilityVerifier.java` created in `com.cajunsystems.roux.testing`
- [ ] `TestCapabilityHandler.java` created in `com.cajunsystems.roux.testing`
- [ ] `TestCapabilityHandler` implements `CapabilityHandler<Capability<?>>` directly (no `.widen()` needed at call sites)
- [ ] `verify(Type.class).neverCalled()` works even for unstubbed capability types (returns empty-list verifier)
- [ ] `TestCapabilityHandlerTest` passes with 10 tests
- [ ] Full test suite passes (no regression)

## Success Criteria

- `stub(Type.class, lambda)` registers a type-safe canned response
- Unregistered capabilities fail effects (UnsupportedOperationException propagates)
- `verify(Type.class).calledOnce()` / `.calledTimes(N)` / `.neverCalled()` all assert correctly
- `calledWith(predicate)` checks argument values across all recorded calls
- Verifier methods are chainable
- Zero production code changes

## Output

Test scope only:
- `lib/src/test/java/com/cajunsystems/roux/testing/CapabilityVerifier.java`
- `lib/src/test/java/com/cajunsystems/roux/testing/TestCapabilityHandler.java`
- `lib/src/test/java/com/cajunsystems/roux/testing/TestCapabilityHandlerTest.java`
