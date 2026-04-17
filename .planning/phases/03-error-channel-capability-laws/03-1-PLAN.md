# Plan: 03-1 — Error Channel & Capability Laws

## Objective
Verify that error-handling combinators and capability handler composition satisfy their algebraic laws. Two test classes: `ErrorChannelLawsTest` (4 laws, 8 tests) and `CapabilityHandlerLawsTest` (2 laws, 6 tests).

## Context

**Laws to verify:**

| # | Law | Expression |
|---|-----|-----------|
| E1 | `catchAll` identity | `effect.catchAll(Effect::fail)` ≡ `effect` |
| E2 | `mapError` identity | `effect.mapError(e -> e)` ≡ `effect` |
| E3 | `mapError` composition | `effect.mapError(f).mapError(g)` ≡ `effect.mapError(f.andThen(g))` |
| E4 | `attempt` round-trip | `effect.attempt().flatMap(e -> e.fold(Effect::fail, Effect::succeed))` ≡ `effect` |
| C1 | `orElse` identity | `handler.orElse(neverHandles)` ≡ `handler` for all capabilities it handles |
| C2 | `compose` associativity | `compose(h1, compose(h2, h3))` ≡ `compose(h1, h2, h3)` for all capabilities |

**Implementation details (from source):**
- `catchAll` = `new Fold(this, handler, Effect::succeed)` — success routes through `Effect::succeed`, error invokes handler
- `mapError` = `new MapError(this, f)` — only applies function on failure path
- `attempt()` = `new Fold(this, e -> succeed(Either.left(e)), a -> succeed(Either.right(a)))` — converts to `Effect<Throwable, Either<E,A>>`
- `orElse` catches `UnsupportedOperationException` from primary handler and delegates to fallback
- `compose` tries each handler in order, first non-UnsupportedOperationException wins

**Error channel laws — test strategy:**
- Use `Effect<Throwable, Integer>` throughout to avoid type-inference ceremony with `widen()`
- Both success and failure variants for each law
- Failure comparison: exception class + message (per `EffectLawSupport` convention)

**Capability handler laws — test strategy:**
- Capability handler laws are about `handle()` dispatch, NOT effect execution
- Do NOT use `EffectLawSupport` — use direct `handle()` calls + regular JUnit assertions
- Define a small `sealed interface TestCapability<R>` with 3 variants: Alpha, Beta, Gamma

**Uncommitted `CapabilityHandler.java` changes:**
- Added `forType(Class<F>)` — a typed alias for `builder()` with better type inference
- Dispatch logic unchanged (exact class, then first interface level)
- No impact on the laws being tested — both `builder()` and `forType()` produce equivalent handlers

## Tasks

### Task 1 — Create `ErrorChannelLawsTest`

Create `lib/src/test/java/com/cajunsystems/roux/laws/ErrorChannelLawsTest.java`:

```java
package com.cajunsystems.roux.laws;

import com.cajunsystems.roux.Effect;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.function.Function;

import static com.cajunsystems.roux.laws.EffectLawSupport.assertEquivalent;

/**
 * Verifies error channel laws for Effect&lt;E,A&gt;.
 *
 * <p>Laws verified:
 * <ol>
 *   <li>catchAll identity:    {@code effect.catchAll(Effect::fail)} ≡ {@code effect}
 *   <li>mapError identity:    {@code effect.mapError(e -> e)} ≡ {@code effect}
 *   <li>mapError composition: {@code effect.mapError(f).mapError(g)} ≡ {@code effect.mapError(f.andThen(g))}
 *   <li>attempt round-trip:   {@code effect.attempt().flatMap(fold)} ≡ {@code effect}
 * </ol>
 */
class ErrorChannelLawsTest {

    // -----------------------------------------------------------------------
    // E1: catchAll identity — effect.catchAll(Effect::fail) ≡ effect
    // -----------------------------------------------------------------------

    @Test
    void catchAllIdentity_succeedingEffect() {
        Effect<Throwable, Integer> effect = Effect.succeed(42);
        assertEquivalent(
                "catchAll-identity",
                effect.catchAll(Effect::fail),
                effect);
    }

    @Test
    void catchAllIdentity_failingEffect() {
        Effect<Throwable, Integer> effect = Effect.fail(new IOException("err"));
        assertEquivalent(
                "catchAll-identity",
                effect.catchAll(Effect::fail),
                effect);
    }

    // -----------------------------------------------------------------------
    // E2: mapError identity — effect.mapError(e -> e) ≡ effect
    // -----------------------------------------------------------------------

    @Test
    void mapErrorIdentity_succeedingEffect() {
        Effect<Throwable, Integer> effect = Effect.succeed(42);
        assertEquivalent(
                "mapError-identity",
                effect.mapError(e -> e),
                effect);
    }

    @Test
    void mapErrorIdentity_failingEffect() {
        Effect<Throwable, Integer> effect = Effect.fail(new IOException("err"));
        assertEquivalent(
                "mapError-identity",
                effect.mapError(e -> e),
                effect);
    }

    // -----------------------------------------------------------------------
    // E3: mapError composition — mapError(f).mapError(g) ≡ mapError(f.andThen(g))
    // -----------------------------------------------------------------------

    @Test
    void mapErrorComposition_succeedingEffect() {
        Function<Throwable, RuntimeException> wrapMsg =
                e -> new RuntimeException("wrapped: " + e.getMessage());
        Function<RuntimeException, IllegalStateException> wrapAgain =
                e -> new IllegalStateException(e.getMessage());

        Effect<Throwable, Integer> effect = Effect.succeed(42);
        assertEquivalent(
                "mapError-composition",
                effect.mapError(wrapMsg).mapError(wrapAgain),
                effect.mapError(wrapMsg.andThen(wrapAgain)));
    }

    @Test
    void mapErrorComposition_failingEffect() {
        Function<Throwable, RuntimeException> wrapMsg =
                e -> new RuntimeException("wrapped: " + e.getMessage());
        Function<RuntimeException, IllegalStateException> wrapAgain =
                e -> new IllegalStateException(e.getMessage());

        Effect<Throwable, Integer> effect = Effect.fail(new IOException("original"));
        assertEquivalent(
                "mapError-composition",
                effect.mapError(wrapMsg).mapError(wrapAgain),
                effect.mapError(wrapMsg.andThen(wrapAgain)));
    }

    // -----------------------------------------------------------------------
    // E4: attempt round-trip — attempt().flatMap(fold) ≡ effect
    // -----------------------------------------------------------------------

    @Test
    void attemptRoundTrip_succeedingEffect() {
        Effect<Throwable, Integer> effect = Effect.succeed(42);
        assertEquivalent(
                "attempt-round-trip",
                effect.attempt().flatMap(either -> either.fold(
                        e -> Effect.fail(e),
                        a -> Effect.succeed(a))),
                effect);
    }

    @Test
    void attemptRoundTrip_failingEffect() {
        Effect<Throwable, Integer> effect = Effect.fail(new IOException("err"));
        assertEquivalent(
                "attempt-round-trip",
                effect.attempt().flatMap(either -> either.fold(
                        e -> Effect.fail(e),
                        a -> Effect.succeed(a))),
                effect);
    }
}
```

### Task 2 — Create `CapabilityHandlerLawsTest`

Create `lib/src/test/java/com/cajunsystems/roux/laws/CapabilityHandlerLawsTest.java`.

**Note:** Capability handler laws verify dispatch behaviour of `handle()` directly. These are NOT effect-level laws — no `EffectLawSupport` or `EffectRuntime` needed.

```java
package com.cajunsystems.roux.laws;

import com.cajunsystems.roux.capability.Capability;
import com.cajunsystems.roux.capability.CapabilityHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies algebraic laws for CapabilityHandler composition.
 *
 * <p>Laws verified:
 * <ol>
 *   <li>orElse identity:     {@code handler.orElse(neverHandles)} ≡ {@code handler} for handled capabilities
 *   <li>compose associativity: {@code compose(h1, compose(h2, h3))} ≡ {@code compose(h1, h2, h3)}
 * </ol>
 */
class CapabilityHandlerLawsTest {

    // -- Test capability domain --
    sealed interface TestCapability<R> extends Capability<R> {
        record Alpha(String value) implements TestCapability<String> {}
        record Beta(int value)    implements TestCapability<Integer> {}
        record Gamma(boolean value) implements TestCapability<Boolean> {}
    }

    private static CapabilityHandler<Capability<?>> alphaHandler() {
        return CapabilityHandler.builder()
                .on(TestCapability.Alpha.class, c -> "alpha:" + c.value())
                .build();
    }

    private static CapabilityHandler<Capability<?>> betaHandler() {
        return CapabilityHandler.builder()
                .on(TestCapability.Beta.class, c -> c.value() * 2)
                .build();
    }

    private static CapabilityHandler<Capability<?>> gammaHandler() {
        return CapabilityHandler.builder()
                .on(TestCapability.Gamma.class, c -> !c.value())
                .build();
    }

    /** A handler that always signals "not handled". */
    private static final CapabilityHandler<Capability<?>> neverHandles =
            new CapabilityHandler<>() {
                @Override
                public <R> R handle(Capability<?> capability) {
                    throw new UnsupportedOperationException("never handles");
                }
            };

    // -----------------------------------------------------------------------
    // C1: orElse identity — handler.orElse(neverHandles) ≡ handler
    // -----------------------------------------------------------------------

    @Test
    void orElseIdentity_handledCapability() throws Exception {
        CapabilityHandler<Capability<?>> h = alphaHandler();
        CapabilityHandler<Capability<?>> composed = h.orElse(neverHandles);

        TestCapability.Alpha cap = new TestCapability.Alpha("test");
        assertEquals(
                (String) h.handle(cap),
                (String) composed.handle(cap),
                "orElse identity: result should match original handler");
    }

    @Test
    void orElseIdentity_unhandledCapabilityThrowsSameException() {
        CapabilityHandler<Capability<?>> h = alphaHandler();
        CapabilityHandler<Capability<?>> composed = h.orElse(neverHandles);

        TestCapability.Beta cap = new TestCapability.Beta(5);
        // Both the plain handler and the composed one should throw UnsupportedOperationException
        // for an unregistered capability type
        assertThrows(UnsupportedOperationException.class, () -> h.handle(cap));
        assertThrows(UnsupportedOperationException.class, () -> composed.handle(cap));
    }

    // -----------------------------------------------------------------------
    // C2: compose associativity — compose(h1, compose(h2, h3)) ≡ compose(h1, h2, h3)
    // -----------------------------------------------------------------------

    @Test
    void composeAssociativity_alphaCapability() throws Exception {
        CapabilityHandler<Capability<?>> flat   = CapabilityHandler.compose(alphaHandler(), betaHandler(), gammaHandler());
        CapabilityHandler<Capability<?>> nested = CapabilityHandler.compose(alphaHandler(), CapabilityHandler.compose(betaHandler(), gammaHandler()));

        TestCapability.Alpha cap = new TestCapability.Alpha("hello");
        assertEquals(
                (String) flat.handle(cap),
                (String) nested.handle(cap),
                "compose associativity: alpha capability");
    }

    @Test
    void composeAssociativity_betaCapability() throws Exception {
        CapabilityHandler<Capability<?>> flat   = CapabilityHandler.compose(alphaHandler(), betaHandler(), gammaHandler());
        CapabilityHandler<Capability<?>> nested = CapabilityHandler.compose(alphaHandler(), CapabilityHandler.compose(betaHandler(), gammaHandler()));

        TestCapability.Beta cap = new TestCapability.Beta(7);
        assertEquals(
                (Integer) flat.handle(cap),
                (Integer) nested.handle(cap),
                "compose associativity: beta capability");
    }

    @Test
    void composeAssociativity_gammaCapability() throws Exception {
        CapabilityHandler<Capability<?>> flat   = CapabilityHandler.compose(alphaHandler(), betaHandler(), gammaHandler());
        CapabilityHandler<Capability<?>> nested = CapabilityHandler.compose(alphaHandler(), CapabilityHandler.compose(betaHandler(), gammaHandler()));

        TestCapability.Gamma cap = new TestCapability.Gamma(true);
        assertEquals(
                (Boolean) flat.handle(cap),
                (Boolean) nested.handle(cap),
                "compose associativity: gamma capability");
    }

    @Test
    void composeAssociativity_unhandledCapabilityThrowsSameException() {
        CapabilityHandler<Capability<?>> flat   = CapabilityHandler.compose(alphaHandler(), betaHandler());
        CapabilityHandler<Capability<?>> nested = CapabilityHandler.compose(alphaHandler(), CapabilityHandler.compose(betaHandler()));

        // Gamma not registered in either — both should throw
        assertThrows(UnsupportedOperationException.class, () -> flat.handle(new TestCapability.Gamma(false)));
        assertThrows(UnsupportedOperationException.class, () -> nested.handle(new TestCapability.Gamma(false)));
    }
}
```

### Task 3 — Run all tests and verify they pass

```bash
cd /Users/pradeep.samuel/roux
./gradlew :lib:test --tests "com.cajunsystems.roux.laws.ErrorChannelLawsTest" --tests "com.cajunsystems.roux.laws.CapabilityHandlerLawsTest" 2>&1 | tail -25
```

Expected: 14/14 tests pass. Fix any issues before marking complete.

**Likely issues to watch for:**
- `mapError` type inference: if `mapError(e -> e)` causes a type error, use explicit `Function<Throwable, Throwable> id = e -> e; effect.mapError(id)`
- `attempt().flatMap(...)` fold lambdas: `Effect::fail` may need explicit type witness; the explicit lambda forms `e -> Effect.fail(e)` and `a -> Effect.succeed(a)` are safer
- Capability handler cast warnings: `(String)`, `(Integer)`, `(Boolean)` casts on `handle()` return are expected (return type is generic `<R>`)

## Verification

- [ ] `ErrorChannelLawsTest.java` created with 8 tests (2 per law × 4 laws)
- [ ] `CapabilityHandlerLawsTest.java` created with 6 tests
- [ ] All 14 tests pass: `BUILD SUCCESSFUL`
- [ ] No new production files

## Success Criteria

- All 6 error channel and capability laws (E1–E4, C1–C2) verified as passing JUnit tests
- Error channel laws tested for both success and failure effect inputs
- Capability laws tested for handled, unhandled, and multi-handler scenarios

## Output

- `lib/src/test/java/com/cajunsystems/roux/laws/ErrorChannelLawsTest.java`
- `lib/src/test/java/com/cajunsystems/roux/laws/CapabilityHandlerLawsTest.java`
