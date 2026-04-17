# Plan: 08-1 — EffectWithEnv

## Objective
Implement `EffectWithEnv<R, E, A>` — a thin wrapper around `Effect<E, A>` that statically declares
which capability environment `R` the effect needs. The `run(HandlerEnv<R>, EffectRuntime)` method
compiles only when the env's phantom type exactly matches the effect's declared requirement.

## Context

**Package:** `com.cajunsystems.roux` — alongside `Effect<E, A>`.

**Key existing types:**
- `com.cajunsystems.roux.Effect<E, A>` — sealed interface; `.map(Function<A,B>)`, `.flatMap(Function<A,Effect<E,B>>)`
- `com.cajunsystems.roux.EffectRuntime` — `unsafeRunWithHandler(Effect<E,A>, CapabilityHandler<Capability<?>>)`
- `com.cajunsystems.roux.capability.HandlerEnv<R>` — typed env; `.toHandler()` → `CapabilityHandler<Capability<?>>`
- `com.cajunsystems.roux.capability.Empty` — phantom type (no capabilities)

**Design:**
`EffectWithEnv<R, E, A>` is a `final class` with a single private field `Effect<E, A> effect`.
The phantom `R` is never used at runtime — it exists only in generics signatures to carry capability
requirements through the type system.

- `.of(Effect<E,A>)` — wrap with caller-declared requirements; `R` must be specified explicitly
  at the call site (Java cannot infer phantom types)
- `.pure(Effect<E,A>)` — wrap with `Empty` requirements; no capabilities needed
- `.map(Function<A,B>)` — delegates to `effect.map(f)`, wraps result, preserves `R`
- `.flatMap(Function<A, EffectWithEnv<R,E,B>>)` — delegates to `effect.flatMap(a -> f.apply(a).effect)`, preserves `R`
- `.run(HandlerEnv<R>, EffectRuntime)` — calls `runtime.unsafeRunWithHandler(effect, env.toHandler())`
- `.effect()` — accessor for interop with untyped APIs

**Type-safety in practice:**
```java
EffectWithEnv<StoreOps, Throwable, String> e = EffectWithEnv.of(new StoreOps.Get("k").toEffect());

HandlerEnv<StoreOps> storeEnv = HandlerEnv.of(StoreOps.class, cap -> "value");
e.run(storeEnv, runtime);  // compiles ✓

HandlerEnv<LogOps> logEnv = HandlerEnv.of(LogOps.class, cap -> Unit.unit());
// e.run(logEnv, runtime);  // compile error: HandlerEnv<LogOps> is not HandlerEnv<StoreOps>
```

**`flatMap` inner access:** `f.apply(a).effect` accesses the private `effect` field of another
`EffectWithEnv` instance. This is legal in Java — private access is per-class, not per-instance.

**Tests:** Defined in `com.cajunsystems.roux.EffectWithEnvTest`. Capability types as class-level
nested sealed interfaces.

## Tasks

### Task 1 — Create `EffectWithEnv`

Create `lib/src/main/java/com/cajunsystems/roux/EffectWithEnv.java`:

```java
package com.cajunsystems.roux;

import com.cajunsystems.roux.capability.Empty;
import com.cajunsystems.roux.capability.HandlerEnv;

import java.util.function.Function;

/**
 * A thin wrapper around {@link Effect}{@code <E, A>} that statically declares,
 * via the phantom type parameter {@code R}, which capability environment the effect needs.
 *
 * <p>{@code R} is erased at runtime — {@code EffectWithEnv} is a single-field wrapper
 * with zero overhead beyond the underlying effect.
 *
 * <h3>Declaring requirements</h3>
 * <pre>{@code
 * // R must be specified explicitly — Java cannot infer phantom types
 * EffectWithEnv<StoreOps, Throwable, String> get =
 *     EffectWithEnv.<StoreOps, Throwable, String>of(new StoreOps.Get("key").toEffect());
 *
 * // For effects with no capability requirements:
 * EffectWithEnv<Empty, RuntimeException, Integer> constant =
 *     EffectWithEnv.pure(Effect.succeed(42));
 * }</pre>
 *
 * <h3>Running</h3>
 * <pre>{@code
 * HandlerEnv<StoreOps> env = HandlerEnv.of(StoreOps.class, cap -> store.get(cap.key()));
 *
 * // Compiles — env covers StoreOps
 * String value = get.run(env, runtime);
 *
 * // Does NOT compile — HandlerEnv<LogOps> is not HandlerEnv<StoreOps>
 * // get.run(logEnv, runtime);
 * }</pre>
 *
 * <h3>Transforming</h3>
 * <pre>{@code
 * EffectWithEnv<StoreOps, Throwable, Integer> length = get.map(String::length);
 *
 * EffectWithEnv<StoreOps, Throwable, String> chained =
 *     get.flatMap(v -> EffectWithEnv.of(new StoreOps.Put("last", v).toEffect()));
 * }</pre>
 */
public final class EffectWithEnv<R, E extends Throwable, A> {

    private final Effect<E, A> effect;

    private EffectWithEnv(Effect<E, A> effect) {
        this.effect = effect;
    }

    /**
     * Wrap {@code effect} with declared capability requirements {@code R}.
     *
     * <p>The caller is responsible for asserting that {@code effect} only uses
     * capabilities covered by {@code R}. Java cannot infer phantom types, so
     * {@code R} must be specified explicitly at the call site:
     * <pre>{@code
     * EffectWithEnv.<StoreOps, Throwable, String>of(new StoreOps.Get("k").toEffect())
     * }</pre>
     */
    public static <R, E extends Throwable, A> EffectWithEnv<R, E, A> of(Effect<E, A> effect) {
        return new EffectWithEnv<>(effect);
    }

    /**
     * Wrap {@code effect} with {@link Empty} requirements — no capabilities needed.
     *
     * <pre>{@code
     * EffectWithEnv<Empty, RuntimeException, Integer> constant =
     *     EffectWithEnv.pure(Effect.succeed(42));
     * constant.run(HandlerEnv.empty(), runtime);
     * }</pre>
     */
    public static <E extends Throwable, A> EffectWithEnv<Empty, E, A> pure(Effect<E, A> effect) {
        return new EffectWithEnv<>(effect);
    }

    /**
     * Transform the result value, preserving the capability requirement {@code R}.
     */
    public <B> EffectWithEnv<R, E, B> map(Function<A, B> f) {
        return new EffectWithEnv<>(effect.map(f));
    }

    /**
     * Chain to another {@code EffectWithEnv} that requires the same environment {@code R},
     * preserving the capability requirement.
     */
    public <B> EffectWithEnv<R, E, B> flatMap(Function<A, EffectWithEnv<R, E, B>> f) {
        return new EffectWithEnv<>(effect.flatMap(a -> f.apply(a).effect));
    }

    /**
     * Run this effect using the provided environment.
     *
     * <p>The compiler verifies that {@code env} covers exactly {@code R} — the same
     * capability set this effect declared. Passing a {@code HandlerEnv<LogOps>} for an
     * {@code EffectWithEnv<StoreOps, ...>} is a compile error.
     *
     * @param env     the capability environment that satisfies {@code R}
     * @param runtime the effect runtime to execute with
     * @return the computed value
     * @throws E if the effect fails
     */
    public A run(HandlerEnv<R> env, EffectRuntime runtime) throws E {
        return runtime.unsafeRunWithHandler(effect, env.toHandler());
    }

    /**
     * Return the underlying {@link Effect} for interop with untyped APIs.
     */
    public Effect<E, A> effect() {
        return effect;
    }
}
```

**After writing, compile check:**
```bash
cd /Users/pradeep.samuel/roux && ./gradlew :lib:compileJava 2>&1 | tail -20
```

Fix any compile errors before committing.

**Commit:**
```
feat(08-1): add EffectWithEnv typed effect wrapper
```
Stage: `lib/src/main/java/com/cajunsystems/roux/EffectWithEnv.java`

### Task 2 — Create `EffectWithEnvTest`

Create `lib/src/test/java/com/cajunsystems/roux/EffectWithEnvTest.java`:

```java
package com.cajunsystems.roux;

import com.cajunsystems.roux.capability.Capability;
import com.cajunsystems.roux.capability.Empty;
import com.cajunsystems.roux.capability.HandlerEnv;
import com.cajunsystems.roux.capability.With;
import com.cajunsystems.roux.data.Unit;
import com.cajunsystems.roux.runtime.DefaultEffectRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EffectWithEnvTest {

    // -----------------------------------------------------------------------
    // Test capability families (class-level — sealed interfaces cannot be local)
    // -----------------------------------------------------------------------

    sealed interface StoreOps extends Capability<String> {
        record Get(String key)               implements StoreOps {}
        record Put(String key, String value) implements StoreOps {}
    }

    sealed interface LogOps extends Capability<Unit> {
        record Info(String message) implements LogOps {}
    }

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
    // EffectWithEnv.pure() — Empty requirements
    // -----------------------------------------------------------------------

    @Test
    void pure_runsWithEmptyEnv() throws Throwable {
        EffectWithEnv<Empty, RuntimeException, Integer> effect =
                EffectWithEnv.pure(Effect.succeed(42));

        Integer result = effect.run(HandlerEnv.empty(), runtime);

        assertEquals(42, result);
    }

    @Test
    void pure_failingEffect_propagatesError() {
        EffectWithEnv<Empty, RuntimeException, Integer> effect =
                EffectWithEnv.pure(Effect.fail(new RuntimeException("boom")));

        assertThrows(RuntimeException.class, () ->
                effect.run(HandlerEnv.empty(), runtime));
    }

    // -----------------------------------------------------------------------
    // EffectWithEnv.of() — declared capability requirements
    // -----------------------------------------------------------------------

    @Test
    void of_runsCapabilityEffectWithMatchingEnv() throws Throwable {
        Map<String, String> store = new HashMap<>();
        store.put("greeting", "hello");

        HandlerEnv<StoreOps> env = HandlerEnv.of(StoreOps.class, cap -> switch (cap) {
            case StoreOps.Get g -> store.getOrDefault(g.key(), "missing");
            case StoreOps.Put p -> { store.put(p.key(), p.value()); yield "ok"; }
        });

        EffectWithEnv<StoreOps, Throwable, String> effect =
                EffectWithEnv.<StoreOps, Throwable, String>of(
                        new StoreOps.Get("greeting").toEffect());

        String result = effect.run(env, runtime);

        assertEquals("hello", result);
    }

    @Test
    void of_putCapability_mutatesState() throws Throwable {
        Map<String, String> store = new HashMap<>();

        HandlerEnv<StoreOps> env = HandlerEnv.of(StoreOps.class, cap -> switch (cap) {
            case StoreOps.Get g -> store.getOrDefault(g.key(), "missing");
            case StoreOps.Put p -> { store.put(p.key(), p.value()); yield "ok"; }
        });

        EffectWithEnv<StoreOps, Throwable, String> effect =
                EffectWithEnv.<StoreOps, Throwable, String>of(
                        new StoreOps.Put("k", "v").toEffect());

        String result = effect.run(env, runtime);

        assertEquals("ok", result);
        assertEquals("v", store.get("k"));
    }

    // -----------------------------------------------------------------------
    // map() — transforms value, preserves R
    // -----------------------------------------------------------------------

    @Test
    void map_transformsResultValue() throws Throwable {
        EffectWithEnv<Empty, RuntimeException, String> base =
                EffectWithEnv.pure(Effect.succeed("hello"));

        EffectWithEnv<Empty, RuntimeException, Integer> mapped = base.map(String::length);

        Integer result = mapped.run(HandlerEnv.empty(), runtime);
        assertEquals(5, result);
    }

    @Test
    void map_preservesCapabilityRequirement() throws Throwable {
        Map<String, String> store = new HashMap<>();
        store.put("name", "Alice");

        HandlerEnv<StoreOps> env = HandlerEnv.of(StoreOps.class, cap -> switch (cap) {
            case StoreOps.Get g -> store.getOrDefault(g.key(), "missing");
            case StoreOps.Put p -> { store.put(p.key(), p.value()); yield "ok"; }
        });

        // map result is still EffectWithEnv<StoreOps, ...> — not EffectWithEnv<Empty, ...>
        EffectWithEnv<StoreOps, Throwable, Integer> nameLength =
                EffectWithEnv.<StoreOps, Throwable, String>of(
                        new StoreOps.Get("name").toEffect())
                        .map(String::length);

        Integer result = nameLength.run(env, runtime);
        assertEquals(5, result); // "Alice".length() == 5
    }

    // -----------------------------------------------------------------------
    // flatMap() — chains effects, preserves R
    // -----------------------------------------------------------------------

    @Test
    void flatMap_chainsEffects() throws Throwable {
        Map<String, String> store = new HashMap<>();
        store.put("key", "Alice");

        HandlerEnv<StoreOps> env = HandlerEnv.of(StoreOps.class, cap -> switch (cap) {
            case StoreOps.Get g -> store.getOrDefault(g.key(), "missing");
            case StoreOps.Put p -> { store.put(p.key(), p.value()); yield "ok"; }
        });

        // Get "key", then copy its value to "backup"
        EffectWithEnv<StoreOps, Throwable, String> effect =
                EffectWithEnv.<StoreOps, Throwable, String>of(
                        new StoreOps.Get("key").toEffect())
                        .flatMap(value ->
                                EffectWithEnv.of(new StoreOps.Put("backup", value).toEffect()));

        String result = effect.run(env, runtime);

        assertEquals("ok", result);
        assertEquals("Alice", store.get("backup"));
    }

    // -----------------------------------------------------------------------
    // Combined environment — With<StoreOps, LogOps>
    // -----------------------------------------------------------------------

    @Test
    void of_withCombinedEnv_handlesBothCapabilities() throws Throwable {
        Map<String, String> store = new HashMap<>();
        store.put("user", "Bob");
        StringBuilder log = new StringBuilder();

        HandlerEnv<With<StoreOps, LogOps>> env = HandlerEnv.of(StoreOps.class, cap -> switch (cap) {
            case StoreOps.Get g -> store.getOrDefault(g.key(), "missing");
            case StoreOps.Put p -> { store.put(p.key(), p.value()); yield "ok"; }
        }).and(HandlerEnv.of(LogOps.class, cap -> switch (cap) {
            case LogOps.Info m -> { log.append(m.message()); yield Unit.unit(); }
        }));

        Effect<Throwable, String> rawEffect = new StoreOps.Get("user").<Throwable>toEffect()
                .flatMap(name -> new LogOps.Info("got: " + name).<Throwable>toEffect()
                        .flatMap(__ -> Effect.succeed(name)));

        EffectWithEnv<With<StoreOps, LogOps>, Throwable, String> effect =
                EffectWithEnv.of(rawEffect);

        String result = effect.run(env, runtime);

        assertEquals("Bob", result);
        assertEquals("got: Bob", log.toString());
    }

    // -----------------------------------------------------------------------
    // effect() accessor
    // -----------------------------------------------------------------------

    @Test
    void effect_returnsUnderlyingEffect() throws Throwable {
        Effect<RuntimeException, Integer> underlying = Effect.succeed(99);
        EffectWithEnv<Empty, RuntimeException, Integer> wrapped = EffectWithEnv.pure(underlying);

        // effect() allows dropping back to untyped API
        Integer result = runtime.unsafeRun(wrapped.effect());
        assertEquals(99, result);
    }
}
```

**Commit:**
```
test(08-1): add EffectWithEnvTest with 9 tests
```
Stage: `lib/src/test/java/com/cajunsystems/roux/EffectWithEnvTest.java`

### Task 3 — Run all tests

```bash
cd /Users/pradeep.samuel/roux
./gradlew :lib:test --tests "com.cajunsystems.roux.EffectWithEnvTest" 2>&1 | tail -30
```

Then full suite:
```bash
./gradlew :lib:test 2>&1 | tail -20
```

All tests must pass.

## Verification

- [ ] `EffectWithEnv<R, E, A>` in `com.cajunsystems.roux` package
- [ ] Private constructor, single `Effect<E, A>` field
- [ ] `.of()` — static factory, `R` is phantom (caller declares)
- [ ] `.pure()` — static factory, `R` is `Empty`
- [ ] `.map(Function<A,B>)` → `EffectWithEnv<R, E, B>` — delegates to `effect.map(f)`
- [ ] `.flatMap(Function<A, EffectWithEnv<R,E,B>>)` → `EffectWithEnv<R,E,B>` — inner effect chained
- [ ] `.run(HandlerEnv<R>, EffectRuntime)` — calls `unsafeRunWithHandler`
- [ ] `.effect()` — exposes underlying effect for interop
- [ ] `EffectWithEnvTest` passes 9 tests
- [ ] Full test suite passes (no regression)

## Success Criteria

- `EffectWithEnv.pure(Effect.succeed(42)).run(HandlerEnv.empty(), runtime)` returns `42`
- `EffectWithEnv.<StoreOps,...>of(capEffect).run(storeEnv, runtime)` dispatches capability correctly
- `map` and `flatMap` preserve `R` and chain correctly
- `effect()` accessor allows dropping back to untyped `Effect<E,A>`
- Zero new production dependencies

## Output

Production:
- `lib/src/main/java/com/cajunsystems/roux/EffectWithEnv.java`

Test:
- `lib/src/test/java/com/cajunsystems/roux/EffectWithEnvTest.java`
