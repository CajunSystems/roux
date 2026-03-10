# Plan: 01-1 — Law Test Infrastructure

## Objective
Create the foundation for effect law testing: a package structure, an `EffectLawSupport` utility with effect equivalence assertions, and a smoke test that validates the infrastructure works before law tests are written in Phase 2.

## Context

**Key facts:**
- Effects are lazy — structural equality is meaningless. Two effects are "equivalent" if they produce the same observable outcome when run.
- Outcome comparison must handle both success (compare values with `assertEquals`) and failure (compare exception class + message, since exceptions use reference equality by default).
- Law tests live in `lib/src/test/java/com/cajunsystems/roux/laws/` — a new test-only package.
- The runtime used for law verification is `DefaultEffectRuntime.create()` (standard runtime, not a test double).
- No new dependencies — JUnit 5 only.

**Existing patterns to follow:**
- Tests use `DefaultEffectRuntime.create()` — see `EffectTest.java:16`
- Exception assertions use `assertThrows` and `assertEquals(message)` — see `EffectTest.java:29-34`
- `Either<L,R>` is a sealed record — `Left` and `Right` subtypes with field-based `equals()` — see `data/Either.java`

**Key design decision:**
`EffectLawSupport` is a utility class with static methods (not an abstract base class). Law test classes are standalone JUnit classes that call `EffectLawSupport.assertEquivalent(...)`. This avoids inheritance coupling and keeps tests readable.

## Tasks

### Task 1 — Create the `laws` package and `EffectLawSupport` utility

Create `lib/src/test/java/com/cajunsystems/roux/laws/EffectLawSupport.java`.

The class provides:

```java
package com.cajunsystems.roux.laws;

import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.EffectRuntime;
import com.cajunsystems.roux.runtime.DefaultEffectRuntime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Utilities for verifying algebraic laws on Effect<E,A>.
 *
 * Effect equivalence is defined observationally: two effects are equivalent
 * if running them produces the same outcome — same success value, or same
 * error class and message.
 *
 * Structural equality is not used because Effects are lazy descriptions;
 * two structurally different effect chains can be observationally equivalent.
 */
public final class EffectLawSupport {

    private EffectLawSupport() {}

    /**
     * Assert that two effects are observationally equivalent under a fresh runtime.
     * Uses DefaultEffectRuntime.create() for execution.
     */
    public static <E extends Throwable, A> void assertEquivalent(
            String lawName,
            Effect<E, A> left,
            Effect<E, A> right
    ) {
        try (EffectRuntime runtime = DefaultEffectRuntime.create()) {
            assertEquivalentWith(lawName, left, right, runtime);
        }
    }

    /**
     * Assert equivalence using a provided runtime (useful for parameterized law suites).
     */
    public static <E extends Throwable, A> void assertEquivalentWith(
            String lawName,
            Effect<E, A> left,
            Effect<E, A> right,
            EffectRuntime runtime
    ) {
        Outcome<A> leftOutcome = runCapturing(runtime, left);
        Outcome<A> rightOutcome = runCapturing(runtime, right);
        assertOutcomesMatch(lawName, leftOutcome, rightOutcome);
    }

    // -- Internals --

    sealed interface Outcome<A> {
        record Success<A>(A value) implements Outcome<A> {}
        record Failure<A>(Throwable error) implements Outcome<A> {}
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable, A> Outcome<A> runCapturing(
            EffectRuntime runtime,
            Effect<E, A> effect
    ) {
        try {
            return new Outcome.Success<>(runtime.unsafeRun(effect));
        } catch (Throwable e) {
            return new Outcome.Failure<>(e);
        }
    }

    private static <A> void assertOutcomesMatch(
            String lawName,
            Outcome<A> left,
            Outcome<A> right
    ) {
        if (left instanceof Outcome.Success<A> ls && right instanceof Outcome.Success<A> rs) {
            assertEquals(ls.value(), rs.value(),
                    "Law [" + lawName + "] violated: success values differ — left=" + ls.value() + ", right=" + rs.value());
        } else if (left instanceof Outcome.Failure<A> lf && right instanceof Outcome.Failure<A> rf) {
            assertEquals(lf.error().getClass(), rf.error().getClass(),
                    "Law [" + lawName + "] violated: error types differ — left=" + lf.error().getClass().getSimpleName() + ", right=" + rf.error().getClass().getSimpleName());
            assertEquals(lf.error().getMessage(), rf.error().getMessage(),
                    "Law [" + lawName + "] violated: error messages differ — left=" + lf.error().getMessage() + ", right=" + rf.error().getMessage());
        } else {
            fail("Law [" + lawName + "] violated: one effect succeeded and the other failed — left=" + left + ", right=" + right);
        }
    }
}
```

**Note:** Check whether `DefaultEffectRuntime` implements `AutoCloseable` (look for a `close()` method). If it does, use try-with-resources. If not, just call `DefaultEffectRuntime.create()` without closing.

### Task 2 — Write the smoke test `LawInfrastructureTest`

Create `lib/src/test/java/com/cajunsystems/roux/laws/LawInfrastructureTest.java`.

This test validates that `EffectLawSupport` itself works correctly — i.e., that equivalent effects pass and non-equivalent effects fail the assertion.

```java
package com.cajunsystems.roux.laws;

import com.cajunsystems.roux.Effect;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static com.cajunsystems.roux.laws.EffectLawSupport.assertEquivalent;
import static org.junit.jupiter.api.Assertions.*;

class LawInfrastructureTest {

    // -- Positive cases: equivalent effects pass --

    @Test
    void succeedingEffectsWithSameValue_areEquivalent() {
        assertEquivalent("smoke",
                Effect.succeed(42),
                Effect.succeed(42));
    }

    @Test
    void failingEffectsWithSameError_areEquivalent() {
        assertEquivalent("smoke",
                Effect.<IOException, Integer>fail(new IOException("boom")),
                Effect.<IOException, Integer>fail(new IOException("boom")));
    }

    @Test
    void chainedEffectsProducingSameResult_areEquivalent() {
        assertEquivalent("smoke",
                Effect.succeed(2).map(x -> x * 21),
                Effect.succeed(42));
    }

    // -- Negative cases: non-equivalent effects fail the assertion --

    @Test
    void effectsWithDifferentValues_areNotEquivalent() {
        AssertionError err = assertThrows(AssertionError.class, () ->
                assertEquivalent("smoke",
                        Effect.succeed(1),
                        Effect.succeed(2))
        );
        assertTrue(err.getMessage().contains("smoke"));
    }

    @Test
    void successVsFailure_areNotEquivalent() {
        assertThrows(AssertionError.class, () ->
                assertEquivalent("smoke",
                        Effect.succeed(42),
                        Effect.fail(new IOException("error")))
        );
    }

    @Test
    void errorsWithDifferentMessages_areNotEquivalent() {
        assertThrows(AssertionError.class, () ->
                assertEquivalent("smoke",
                        Effect.<IOException, Integer>fail(new IOException("error-a")),
                        Effect.<IOException, Integer>fail(new IOException("error-b")))
        );
    }
}
```

### Task 3 — Run the tests and verify they pass

```bash
cd /Users/pradeep.samuel/roux
./gradlew :lib:test --tests "com.cajunsystems.roux.laws.*" 2>&1 | tail -20
```

All 6 smoke tests must pass. Fix any compilation errors or test failures before marking this plan complete.

## Verification

- [ ] `EffectLawSupport.java` created in `com.cajunsystems.roux.laws` package
- [ ] `LawInfrastructureTest.java` created with 6 tests (3 positive, 3 negative)
- [ ] `./gradlew :lib:test --tests "com.cajunsystems.roux.laws.*"` passes with 6/6 tests green
- [ ] No new production source files created — test scope only

## Success Criteria

- `EffectLawSupport.assertEquivalent()` correctly identifies equivalent effects (same value, same error class+message)
- `EffectLawSupport.assertEquivalent()` correctly rejects non-equivalent effects with a readable message that includes the law name
- The `laws` package structure is in place, ready for Phases 2 and 3

## Output

- `lib/src/test/java/com/cajunsystems/roux/laws/EffectLawSupport.java`
- `lib/src/test/java/com/cajunsystems/roux/laws/LawInfrastructureTest.java`
