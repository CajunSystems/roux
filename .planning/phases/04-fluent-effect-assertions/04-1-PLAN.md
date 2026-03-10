# Plan: 04-1 — Fluent Effect Assertions

## Objective
Create a clean, readable assertion API for effect-based tests. Developers should never need to manually call `unsafeRun` and catch exceptions in test code — `EffectAssertions.assertThat(effect)` handles execution and provides expressive fluent assertions.

## Context

**Target API:**
```java
EffectAssertions.assertThat(Effect.succeed(42))
    .succeeds()
    .succeedsWith(42)
    .succeedsWith(v -> v > 0);

EffectAssertions.assertThat(Effect.fail(new IOException("boom")))
    .fails()
    .failsWith(IOException.class)
    .failsWith(e -> e.getMessage().contains("boom"));

// With capability handler:
EffectAssertions.assertThat(capabilityEffect, handler)
    .succeedsWith("expected result");

// Extract value for custom assertions:
String result = EffectAssertions.assertThat(effect).andReturn();
IOException err  = EffectAssertions.assertThat(effect).andError();
```

**Package:** `com.cajunsystems.roux.testing` — dedicated test utility package, separate from `laws`

**Two classes:**
- `EffectAssertions` — static entry points, runs the effect, returns `EffectAssert`
- `EffectAssert<E, A>` — fluent chain holding the captured outcome

**Key design decision: eager execution**
The effect runs once when `EffectAssertions.assertThat(effect)` is called. The outcome is stored in `EffectAssert`. All subsequent assertion methods operate on the stored outcome — no re-execution. This ensures consistent results when chaining multiple assertions.

**Runtime:** `DefaultEffectRuntime.create()` used internally with try-with-resources. Users don't manage runtimes in their assertion code.

**Assertion methods:**
| Method | Description |
|--------|-------------|
| `succeeds()` | Asserts effect succeeded (any value) |
| `succeedsWith(A expected)` | Asserts succeeded with exact value |
| `succeedsWith(Predicate<A>)` | Asserts succeeded and predicate holds |
| `fails()` | Asserts effect failed (any error) |
| `failsWith(Class<? extends Throwable>)` | Asserts failed with specific type |
| `failsWith(Predicate<? super Throwable>)` | Asserts failed and predicate holds |
| `andReturn()` | Asserts success and returns value for custom assertions |
| `andError()` | Asserts failure and returns error for custom assertions |

**All methods return `this`** (except `andReturn()` / `andError()`) for fluent chaining.

**Imports note:** `EffectAssert` imports `org.junit.jupiter.api.Assertions.*` for `fail()`, `assertEquals()`, `assertTrue()`, `assertInstanceOf()` — these are already on the test classpath.

## Tasks

### Task 1 — Create `EffectAssert` fluent chain

Create `lib/src/test/java/com/cajunsystems/roux/testing/EffectAssert.java`:

```java
package com.cajunsystems.roux.testing;

import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fluent assertion chain for a single executed Effect outcome.
 *
 * <p>The effect is run once at construction time. All assertion methods
 * operate on the stored outcome — there is no re-execution.
 *
 * <p>All assertion methods return {@code this} for chaining, except
 * {@link #andReturn()} and {@link #andError()} which extract values.
 */
public final class EffectAssert<E extends Throwable, A> {

    sealed interface Outcome<A> {
        record Success<A>(A value)     implements Outcome<A> {}
        record Failure<A>(Throwable error) implements Outcome<A> {}
    }

    private final Outcome<A> outcome;

    EffectAssert(Outcome<A> outcome) {
        this.outcome = outcome;
    }

    // -----------------------------------------------------------------------
    // Success assertions
    // -----------------------------------------------------------------------

    /** Assert the effect succeeded with any value. */
    public EffectAssert<E, A> succeeds() {
        if (outcome instanceof Outcome.Failure<A> f) {
            fail("Expected effect to succeed, but it failed with: "
                    + f.error().getClass().getSimpleName() + ": " + f.error().getMessage());
        }
        return this;
    }

    /** Assert the effect succeeded with the exact expected value. */
    public EffectAssert<E, A> succeedsWith(A expected) {
        succeeds();
        A actual = ((Outcome.Success<A>) outcome).value();
        assertEquals(expected, actual,
                "Effect succeeded but value does not match");
        return this;
    }

    /** Assert the effect succeeded and the value satisfies the given predicate. */
    public EffectAssert<E, A> succeedsWith(Predicate<A> predicate) {
        succeeds();
        A actual = ((Outcome.Success<A>) outcome).value();
        assertTrue(predicate.test(actual),
                "Effect succeeded with value=" + actual + " but predicate was not satisfied");
        return this;
    }

    // -----------------------------------------------------------------------
    // Failure assertions
    // -----------------------------------------------------------------------

    /** Assert the effect failed with any error. */
    public EffectAssert<E, A> fails() {
        if (outcome instanceof Outcome.Success<A> s) {
            fail("Expected effect to fail, but it succeeded with: " + s.value());
        }
        return this;
    }

    /** Assert the effect failed with an error of the given type (exact or subtype). */
    public EffectAssert<E, A> failsWith(Class<? extends Throwable> errorType) {
        fails();
        Throwable error = ((Outcome.Failure<A>) outcome).error();
        assertInstanceOf(errorType, error,
                "Effect failed but error type does not match — expected "
                + errorType.getSimpleName() + " but got " + error.getClass().getSimpleName());
        return this;
    }

    /** Assert the effect failed and the error satisfies the given predicate. */
    public EffectAssert<E, A> failsWith(Predicate<? super Throwable> predicate) {
        fails();
        Throwable error = ((Outcome.Failure<A>) outcome).error();
        assertTrue(predicate.test(error),
                "Effect failed with " + error.getClass().getSimpleName()
                + ": " + error.getMessage() + " but predicate was not satisfied");
        return this;
    }

    // -----------------------------------------------------------------------
    // Value extraction
    // -----------------------------------------------------------------------

    /**
     * Assert the effect succeeded and return the value for further custom assertions.
     * Useful when built-in assertion methods aren't sufficient.
     */
    public A andReturn() {
        succeeds();
        return ((Outcome.Success<A>) outcome).value();
    }

    /**
     * Assert the effect failed and return the error for further custom assertions.
     * The error is returned as the declared error type {@code E}.
     */
    @SuppressWarnings("unchecked")
    public E andError() {
        fails();
        return (E) ((Outcome.Failure<A>) outcome).error();
    }
}
```

### Task 2 — Create `EffectAssertions` entry point

Create `lib/src/test/java/com/cajunsystems/roux/testing/EffectAssertions.java`:

```java
package com.cajunsystems.roux.testing;

import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.capability.Capability;
import com.cajunsystems.roux.capability.CapabilityHandler;
import com.cajunsystems.roux.runtime.DefaultEffectRuntime;

/**
 * Entry point for fluent Effect assertions.
 *
 * <p>Usage:
 * <pre>{@code
 * EffectAssertions.assertThat(Effect.succeed(42))
 *     .succeeds()
 *     .succeedsWith(42)
 *     .succeedsWith(v -> v > 0);
 *
 * EffectAssertions.assertThat(Effect.fail(new IOException("boom")))
 *     .fails()
 *     .failsWith(IOException.class)
 *     .failsWith(e -> e.getMessage().contains("boom"));
 *
 * // With capability handler:
 * EffectAssertions.assertThat(capabilityEffect, handler)
 *     .succeedsWith("expected");
 * }</pre>
 *
 * <p>The effect is executed once when {@code assertThat} is called.
 * All chained assertions operate on the stored outcome.
 */
public final class EffectAssertions {

    private EffectAssertions() {}

    /**
     * Execute the effect using a fresh {@link DefaultEffectRuntime} and return
     * a fluent {@link EffectAssert} for making assertions about the outcome.
     */
    public static <E extends Throwable, A> EffectAssert<E, A> assertThat(
            Effect<E, A> effect
    ) {
        try (DefaultEffectRuntime runtime = DefaultEffectRuntime.create()) {
            return new EffectAssert<>(runCapturing(runtime, effect));
        }
    }

    /**
     * Execute the effect with the given capability handler and return a fluent
     * {@link EffectAssert} for making assertions about the outcome.
     */
    public static <E extends Throwable, A> EffectAssert<E, A> assertThat(
            Effect<E, A> effect,
            CapabilityHandler<Capability<?>> handler
    ) {
        try (DefaultEffectRuntime runtime = DefaultEffectRuntime.create()) {
            return new EffectAssert<>(runCapturingWithHandler(runtime, effect, handler));
        }
    }

    // -- Internals --

    private static <E extends Throwable, A> EffectAssert.Outcome<A> runCapturing(
            DefaultEffectRuntime runtime,
            Effect<E, A> effect
    ) {
        try {
            return new EffectAssert.Outcome.Success<>(runtime.unsafeRun(effect));
        } catch (Throwable e) {
            return new EffectAssert.Outcome.Failure<>(e);
        }
    }

    private static <E extends Throwable, A> EffectAssert.Outcome<A> runCapturingWithHandler(
            DefaultEffectRuntime runtime,
            Effect<E, A> effect,
            CapabilityHandler<Capability<?>> handler
    ) {
        try {
            return new EffectAssert.Outcome.Success<>(runtime.unsafeRunWithHandler(effect, handler));
        } catch (Throwable e) {
            return new EffectAssert.Outcome.Failure<>(e);
        }
    }
}
```

### Task 3 — Create `EffectAssertionsTest`

Create `lib/src/test/java/com/cajunsystems/roux/testing/EffectAssertionsTest.java` to verify every assertion method works correctly in both the passing and failing direction:

```java
package com.cajunsystems.roux.testing;

import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.capability.Capability;
import com.cajunsystems.roux.capability.CapabilityHandler;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static com.cajunsystems.roux.testing.EffectAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class EffectAssertionsTest {

    // -- succeeds() --

    @Test
    void succeeds_passesForSucceedingEffect() {
        assertThat(Effect.succeed(42)).succeeds();
    }

    @Test
    void succeeds_failsForFailingEffect() {
        assertThrows(AssertionError.class, () ->
                assertThat(Effect.fail(new IOException("boom"))).succeeds());
    }

    // -- succeedsWith(value) --

    @Test
    void succeedsWith_value_passesForMatchingValue() {
        assertThat(Effect.succeed(42)).succeedsWith(42);
    }

    @Test
    void succeedsWith_value_failsForWrongValue() {
        assertThrows(AssertionError.class, () ->
                assertThat(Effect.succeed(42)).succeedsWith(99));
    }

    @Test
    void succeedsWith_value_failsForFailingEffect() {
        assertThrows(AssertionError.class, () ->
                assertThat(Effect.<IOException, Integer>fail(new IOException("err")))
                        .succeedsWith(42));
    }

    // -- succeedsWith(predicate) --

    @Test
    void succeedsWith_predicate_passesWhenPredicateHolds() {
        assertThat(Effect.succeed(42)).succeedsWith(v -> v > 0);
    }

    @Test
    void succeedsWith_predicate_failsWhenPredicateDoesNotHold() {
        assertThrows(AssertionError.class, () ->
                assertThat(Effect.succeed(42)).succeedsWith(v -> v > 100));
    }

    // -- fails() --

    @Test
    void fails_passesForFailingEffect() {
        assertThat(Effect.fail(new IOException("err"))).fails();
    }

    @Test
    void fails_failsForSucceedingEffect() {
        assertThrows(AssertionError.class, () ->
                assertThat(Effect.succeed(42)).fails());
    }

    // -- failsWith(Class) --

    @Test
    void failsWith_class_passesForCorrectExceptionType() {
        assertThat(Effect.fail(new IOException("err")))
                .failsWith(IOException.class);
    }

    @Test
    void failsWith_class_passesForSubtype() {
        // IOException is a subtype of Exception
        assertThat(Effect.fail(new IOException("err")))
                .failsWith(Exception.class);
    }

    @Test
    void failsWith_class_failsForWrongExceptionType() {
        assertThrows(AssertionError.class, () ->
                assertThat(Effect.fail(new IOException("err")))
                        .failsWith(RuntimeException.class));
    }

    // -- failsWith(predicate) --

    @Test
    void failsWith_predicate_passesWhenPredicateHolds() {
        assertThat(Effect.fail(new IOException("boom")))
                .failsWith(e -> e.getMessage().contains("boom"));
    }

    @Test
    void failsWith_predicate_failsWhenPredicateDoesNotHold() {
        assertThrows(AssertionError.class, () ->
                assertThat(Effect.fail(new IOException("boom")))
                        .failsWith(e -> e.getMessage().contains("xyz")));
    }

    // -- chaining --

    @Test
    void chainedAssertions_allPass() {
        assertThat(Effect.succeed(42))
                .succeeds()
                .succeedsWith(42)
                .succeedsWith(v -> v > 0 && v < 100);
    }

    @Test
    void chainedFailureAssertions_allPass() {
        assertThat(Effect.<IOException, String>fail(new IOException("network error")))
                .fails()
                .failsWith(IOException.class)
                .failsWith(e -> e.getMessage().startsWith("network"));
    }

    // -- andReturn() / andError() --

    @Test
    void andReturn_returnsValueForCustomAssertions() {
        Integer value = assertThat(Effect.succeed(42)).andReturn();
        assertEquals(42, value);
    }

    @Test
    void andReturn_throwsForFailingEffect() {
        assertThrows(AssertionError.class, () ->
                assertThat(Effect.<IOException, Integer>fail(new IOException("err"))).andReturn());
    }

    @Test
    void andError_returnsErrorForCustomAssertions() {
        IOException err = assertThat(Effect.<IOException, String>fail(new IOException("details"))).andError();
        assertEquals("details", err.getMessage());
    }

    @Test
    void andError_throwsForSucceedingEffect() {
        assertThrows(AssertionError.class, () ->
                assertThat(Effect.succeed(42)).andError());
    }

    // -- handler overload --

    @Test
    void assertThatWithHandler_executesCapabilityEffect() {
        sealed interface Greet<R> extends Capability<R> {
            record Hello(String name) implements Greet<String> {}
        }
        CapabilityHandler<Capability<?>> handler = CapabilityHandler.builder()
                .on(Greet.Hello.class, c -> "Hello, " + c.name())
                .build();
        Effect<Throwable, String> effect = new Greet.Hello("World").toEffect();

        assertThat(effect, handler).succeedsWith("Hello, World");
    }
}
```

### Task 4 — Run tests and verify all pass

```bash
cd /Users/pradeep.samuel/roux
./gradlew :lib:test --tests "com.cajunsystems.roux.testing.EffectAssertionsTest" 2>&1 | tail -20
```

Expected: all 22 tests pass. Fix any compilation or test failures.

## Verification

- [ ] `EffectAssert.java` created in `com.cajunsystems.roux.testing`
- [ ] `EffectAssertions.java` created in `com.cajunsystems.roux.testing`
- [ ] `EffectAssertionsTest.java` created with ~22 tests covering all methods
- [ ] All tests pass: `BUILD SUCCESSFUL`
- [ ] No new production source files

## Success Criteria

- `EffectAssertions.assertThat(effect)` runs the effect once and provides fluent assertions
- All 8 assertion methods work correctly (pass and fail directions)
- Handler overload works for capability effects
- `andReturn()` and `andError()` provide escape hatches for custom assertions
- Chaining works: `.succeeds().succeedsWith(42).succeedsWith(v -> v > 0)`

## Output

- `lib/src/test/java/com/cajunsystems/roux/testing/EffectAssert.java`
- `lib/src/test/java/com/cajunsystems/roux/testing/EffectAssertions.java`
- `lib/src/test/java/com/cajunsystems/roux/testing/EffectAssertionsTest.java`
