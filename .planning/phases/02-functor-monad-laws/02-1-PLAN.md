# Plan: 02-1 — Functor & Monad Laws

## Objective
Implement and verify the 5 core algebraic laws that define correct `Effect` behavior as a functor and monad. Each law is expressed as a JUnit test using `EffectLawSupport.assertEquivalent()`. Laws are tested with both success and failure inputs to ensure they hold under all observable effect outcomes.

## Context

**Laws to verify:**

| # | Law | Expression |
|---|-----|-----------|
| F1 | Functor identity | `effect.map(x -> x)` ≡ `effect` |
| F2 | Functor composition | `effect.map(f.andThen(g))` ≡ `effect.map(f).map(g)` |
| M1 | Monad left identity | `Effect.succeed(a).flatMap(f)` ≡ `f.apply(a)` |
| M2 | Monad right identity | `effect.flatMap(Effect::succeed)` ≡ `effect` |
| M3 | Monad associativity | `effect.flatMap(f).flatMap(g)` ≡ `effect.flatMap(a -> f.apply(a).flatMap(g))` |

**Key implementation insight from `Effect.java:146`:**
```java
default <B> Effect<E, B> map(Function<A, B> f) {
    return flatMap(a -> succeed(f.apply(a)));
}
```
`map` is derived from `flatMap`. This means functor laws hold iff monad laws hold in the right way — the tests confirm this observationally regardless.

**On failure effects and laws:**
- F1 with failure: `failEffect.map(id)` — `map` calls `flatMap`; `flatMap` on a failure propagates the failure without calling `f`. Both sides fail with the same error. ✓
- F2 with failure: same reasoning — composition inside `map` is never reached on failure. ✓
- M1: Only meaningful for success — the law is specifically about `Effect.succeed(a).flatMap(f)`. No failure variant needed.
- M2 with failure: `failEffect.flatMap(Effect::succeed)` — flatMap propagates failure, so both sides fail identically. ✓
- M3 with failure: `failEffect.flatMap(f).flatMap(g)` — failure propagates through both flatMaps. Both sides fail identically. ✓

**Infrastructure available:**
- `EffectLawSupport.assertEquivalent(lawName, left, right)` — from Phase 1
- Package: `com.cajunsystems.roux.laws`
- Runtime: `DefaultEffectRuntime.create()` (fresh per assertion)

**Test inputs to use (concrete, readable):**
- Success: `Effect.succeed(42)` — integer, clear equality
- Failure: `Effect.fail(new IOException("err"))` — typed, identifiable
- Suspend (lazy): `Effect.suspend(() -> 42)` — validates laws hold for lazy effects
- Functions: `f = a -> Effect.succeed(a + 1)`, `g = b -> Effect.succeed(b * 2)`

## Tasks

### Task 1 — Create `FunctorLawsTest`

Create `lib/src/test/java/com/cajunsystems/roux/laws/FunctorLawsTest.java`:

```java
package com.cajunsystems.roux.laws;

import com.cajunsystems.roux.Effect;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.function.Function;

import static com.cajunsystems.roux.laws.EffectLawSupport.assertEquivalent;

/**
 * Verifies functor laws for Effect&lt;E,A&gt;.
 *
 * <p>A functor must satisfy:
 * <ol>
 *   <li>Identity:    {@code effect.map(x -> x)} ≡ {@code effect}
 *   <li>Composition: {@code effect.map(f.andThen(g))} ≡ {@code effect.map(f).map(g)}
 * </ol>
 */
class FunctorLawsTest {

    // Shared test functions
    private static final Function<Integer, Integer> addOne  = x -> x + 1;
    private static final Function<Integer, Integer> timesTwo = x -> x * 2;

    // -----------------------------------------------------------------------
    // F1: Identity — map(id) ≡ identity
    // -----------------------------------------------------------------------

    @Test
    void identity_succeedingEffect() {
        assertEquivalent(
                "functor-identity",
                Effect.succeed(42).map(x -> x),
                Effect.succeed(42));
    }

    @Test
    void identity_failingEffect() {
        assertEquivalent(
                "functor-identity",
                Effect.<IOException, Integer>fail(new IOException("err")).map(x -> x),
                Effect.fail(new IOException("err")));
    }

    @Test
    void identity_suspendEffect() {
        assertEquivalent(
                "functor-identity",
                Effect.<Throwable, Integer>suspend(() -> 42).map(x -> x),
                Effect.suspend(() -> 42));
    }

    // -----------------------------------------------------------------------
    // F2: Composition — map(f.andThen(g)) ≡ map(f).map(g)
    // -----------------------------------------------------------------------

    @Test
    void composition_succeedingEffect() {
        assertEquivalent(
                "functor-composition",
                Effect.succeed(3).map(addOne.andThen(timesTwo)),
                Effect.succeed(3).map(addOne).map(timesTwo));
    }

    @Test
    void composition_failingEffect() {
        assertEquivalent(
                "functor-composition",
                Effect.<IOException, Integer>fail(new IOException("err"))
                        .map(addOne.andThen(timesTwo)),
                Effect.<IOException, Integer>fail(new IOException("err"))
                        .map(addOne).map(timesTwo));
    }

    @Test
    void composition_suspendEffect() {
        assertEquivalent(
                "functor-composition",
                Effect.<Throwable, Integer>suspend(() -> 5).map(addOne.andThen(timesTwo)),
                Effect.<Throwable, Integer>suspend(() -> 5).map(addOne).map(timesTwo));
    }
}
```

### Task 2 — Create `MonadLawsTest`

Create `lib/src/test/java/com/cajunsystems/roux/laws/MonadLawsTest.java`:

```java
package com.cajunsystems.roux.laws;

import com.cajunsystems.roux.Effect;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.function.Function;

import static com.cajunsystems.roux.laws.EffectLawSupport.assertEquivalent;

/**
 * Verifies monad laws for Effect&lt;E,A&gt;.
 *
 * <p>A monad must satisfy:
 * <ol>
 *   <li>Left identity:  {@code Effect.succeed(a).flatMap(f)} ≡ {@code f.apply(a)}
 *   <li>Right identity: {@code effect.flatMap(Effect::succeed)} ≡ {@code effect}
 *   <li>Associativity:  {@code effect.flatMap(f).flatMap(g)}
 *                       ≡ {@code effect.flatMap(a -> f.apply(a).flatMap(g))}
 * </ol>
 */
class MonadLawsTest {

    // Shared test functions
    private static final Function<Integer, Effect<Throwable, Integer>> addOne =
            a -> Effect.succeed(a + 1);
    private static final Function<Integer, Effect<Throwable, Integer>> timesTwo =
            b -> Effect.succeed(b * 2);

    // -----------------------------------------------------------------------
    // M1: Left identity — succeed(a).flatMap(f) ≡ f(a)
    // -----------------------------------------------------------------------

    @Test
    void leftIdentity_withValue() {
        int a = 42;
        assertEquivalent(
                "monad-left-identity",
                Effect.<Throwable, Integer>succeed(a).flatMap(addOne),
                addOne.apply(a));
    }

    @Test
    void leftIdentity_withDifferentValue() {
        int a = 0;
        assertEquivalent(
                "monad-left-identity",
                Effect.<Throwable, Integer>succeed(a).flatMap(timesTwo),
                timesTwo.apply(a));
    }

    // -----------------------------------------------------------------------
    // M2: Right identity — effect.flatMap(succeed) ≡ effect
    // -----------------------------------------------------------------------

    @Test
    void rightIdentity_succeedingEffect() {
        assertEquivalent(
                "monad-right-identity",
                Effect.<Throwable, Integer>succeed(42).flatMap(Effect::succeed),
                Effect.succeed(42));
    }

    @Test
    void rightIdentity_failingEffect() {
        assertEquivalent(
                "monad-right-identity",
                Effect.<IOException, Integer>fail(new IOException("err"))
                        .<Integer>flatMap(Effect::succeed)
                        .widen(),
                Effect.<IOException, Integer>fail(new IOException("err")).widen());
    }

    @Test
    void rightIdentity_suspendEffect() {
        assertEquivalent(
                "monad-right-identity",
                Effect.<Throwable, Integer>suspend(() -> 7).flatMap(Effect::succeed),
                Effect.suspend(() -> 7));
    }

    // -----------------------------------------------------------------------
    // M3: Associativity — (effect.flatMap(f)).flatMap(g) ≡ effect.flatMap(a -> f(a).flatMap(g))
    // -----------------------------------------------------------------------

    @Test
    void associativity_succeedingEffect() {
        assertEquivalent(
                "monad-associativity",
                Effect.<Throwable, Integer>succeed(3).flatMap(addOne).flatMap(timesTwo),
                Effect.<Throwable, Integer>succeed(3).flatMap(a -> addOne.apply(a).flatMap(timesTwo)));
    }

    @Test
    void associativity_failingEffect() {
        assertEquivalent(
                "monad-associativity",
                Effect.<Throwable, Integer>fail(new RuntimeException("fail"))
                        .flatMap(addOne).flatMap(timesTwo),
                Effect.<Throwable, Integer>fail(new RuntimeException("fail"))
                        .flatMap(a -> addOne.apply(a).flatMap(timesTwo)));
    }

    @Test
    void associativity_suspendEffect() {
        assertEquivalent(
                "monad-associativity",
                Effect.<Throwable, Integer>suspend(() -> 10).flatMap(addOne).flatMap(timesTwo),
                Effect.<Throwable, Integer>suspend(() -> 10).flatMap(a -> addOne.apply(a).flatMap(timesTwo)));
    }
}
```

**Note on `rightIdentity_failingEffect`:** The failure case requires `widen()` on both sides because `flatMap` on `Effect<IOException, Integer>` returns `Effect<IOException, Integer>`, but `Effect::succeed` is `<A> Effect<E, A> -> succeed(a)` and the inference makes both sides `Effect<Throwable, Integer>`. Use `.widen()` on both sides to align the error type if needed — or use `Effect.<Throwable, Integer>fail(...)` directly to avoid the issue.

Simplify to avoid type wrestling:

```java
@Test
void rightIdentity_failingEffect() {
    Effect<Throwable, Integer> base = Effect.fail(new IOException("err"));
    assertEquivalent(
            "monad-right-identity",
            base.flatMap(Effect::succeed),
            base);
}
```

### Task 3 — Run tests and verify all pass

```bash
cd /Users/pradeep.samuel/roux
./gradlew :lib:test --tests "com.cajunsystems.roux.laws.FunctorLawsTest" --tests "com.cajunsystems.roux.laws.MonadLawsTest" 2>&1 | tail -20
```

All tests must be green. Fix any type-inference issues in the failure/widen cases — Java's type inference for `flatMap(Effect::succeed)` on typed failures may require explicit type witnesses.

## Verification

- [ ] `FunctorLawsTest.java` created with 6 tests (3 identity, 3 composition)
- [ ] `MonadLawsTest.java` created with 9 tests (2 left identity, 3 right identity, 3 associativity)
- [ ] All 15 tests pass: `BUILD SUCCESSFUL`
- [ ] No new production files

## Success Criteria

- All 5 algebraic laws (F1, F2, M1, M2, M3) verified as passing JUnit tests
- Laws verified for success effects, failure effects, and lazy suspend effects
- Test names clearly identify which law and which input variant is being tested

## Output

- `lib/src/test/java/com/cajunsystems/roux/laws/FunctorLawsTest.java`
- `lib/src/test/java/com/cajunsystems/roux/laws/MonadLawsTest.java`
