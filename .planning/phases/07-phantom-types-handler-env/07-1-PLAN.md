# Plan: 07-1 — Phantom Types + HandlerEnv

## Objective
Establish the compile-time foundation for the type-safe layer system: two phantom marker interfaces
(`Empty`, `With<A, B>`) that are never instantiated, and `HandlerEnv<R>` — a thin wrapper around
`CapabilityHandler<Capability<?>>` whose phantom type parameter `R` tracks which capabilities it
covers at compile time.

## Context

**Package:** All three types go in `com.cajunsystems.roux.capability` — they are capability
infrastructure and live alongside `Capability` and `CapabilityHandler`.

**Key existing types:**
- `com.cajunsystems.roux.capability.Capability<R>` — marker interface; subtypes declare return type via `R`
- `com.cajunsystems.roux.capability.CapabilityHandler<C>` — has `.builder()`, `.orElse()`, `.compose()`
- `com.cajunsystems.roux.data.ThrowingFunction<A, B>` — `B apply(A) throws Exception`
- `com.cajunsystems.roux.EffectRuntime` — `unsafeRunWithHandler(effect, CapabilityHandler<Capability<?>>)`

**F-bound pattern:**
```java
static <R, C extends Capability<R>> HandlerEnv<C> of(Class<C> type, ThrowingFunction<C, R> handler)
```
`C extends Capability<R>` links the capability type to its declared return type. The compiler rejects
handlers that return the wrong type. The phantom on `HandlerEnv` is `C` (the capability family), not
`R` (the return type).

**`and()` return type:**
```java
public <S> HandlerEnv<With<R, S>> and(HandlerEnv<S> other)
```
Uses `CapabilityHandler.orElse()` at runtime; the `With<R, S>` phantom is purely compile-time.

**`HandlerEnv` internals:**
- Single field: `CapabilityHandler<Capability<?>> handler`
- Private constructor; all creation through static factories
- `toHandler()` returns the wrapped handler for passing to `unsafeRunWithHandler`

**Tests:** `HandlerEnvTest` calls `toHandler().handle(cap)` directly to verify dispatch.
Test capability types are class-level nested sealed interfaces (per Phase 4 lesson: sealed
interfaces cannot be local).

## Tasks

### Task 1 — Create `Empty`

Create `lib/src/main/java/com/cajunsystems/roux/capability/Empty.java`:

```java
package com.cajunsystems.roux.capability;

/**
 * Phantom type representing an empty capability environment — no capabilities required.
 *
 * <p>Never instantiated; exists only to carry type information at compile time.
 * Used as the base case for {@link HandlerEnv}:
 * <pre>{@code
 * HandlerEnv<Empty> env = HandlerEnv.empty();
 * }</pre>
 */
public interface Empty {}
```

### Task 2 — Create `With<A, B>`

Create `lib/src/main/java/com/cajunsystems/roux/capability/With.java`:

```java
package com.cajunsystems.roux.capability;

/**
 * Phantom type representing a capability environment that contains both {@code A} and {@code B}.
 *
 * <p>Never instantiated; exists only to carry type information at compile time.
 * Nest right-associatively for three or more capabilities:
 * <pre>{@code
 * HandlerEnv<With<DbOps, With<EmailOps, ConfigOps>>> env = ...;
 * }</pre>
 *
 * <p>Created automatically by {@link HandlerEnv#and(HandlerEnv)}:
 * <pre>{@code
 * HandlerEnv<With<StoreOps, LogOps>> full = storeEnv.and(logEnv);
 * }</pre>
 */
public interface With<A, B> {}
```

### Task 3 — Create `HandlerEnv<R>`

Create `lib/src/main/java/com/cajunsystems/roux/capability/HandlerEnv.java`:

```java
package com.cajunsystems.roux.capability;

import com.cajunsystems.roux.data.ThrowingFunction;

/**
 * A typed capability environment that wraps a {@link CapabilityHandler} and tracks,
 * at compile time, which capabilities it covers via the phantom type parameter {@code R}.
 *
 * <p>The phantom type {@code R} is erased at runtime — {@code HandlerEnv} is a
 * single-field wrapper with zero overhead beyond the underlying handler.
 *
 * <h3>Creating environments</h3>
 * <pre>{@code
 * // Single capability family — R must be consistent across all variants
 * HandlerEnv<StoreOps> storeEnv = HandlerEnv.of(StoreOps.class, cap -> switch (cap) {
 *     case StoreOps.Get g  -> store.getOrDefault(g.key(), "missing");
 *     case StoreOps.Put p  -> { store.put(p.key(), p.value()); yield "ok"; }
 * });
 * }</pre>
 *
 * <h3>Combining environments</h3>
 * <pre>{@code
 * HandlerEnv<With<StoreOps, LogOps>> full = storeEnv.and(logEnv);
 * }</pre>
 *
 * <h3>Running effects</h3>
 * <pre>{@code
 * runtime.unsafeRunWithHandler(effect, env.toHandler());
 * // Phase 8 will add: effectWithEnv.run(env, runtime)
 * }</pre>
 */
public final class HandlerEnv<R> {

    private final CapabilityHandler<Capability<?>> handler;

    private HandlerEnv(CapabilityHandler<Capability<?>> handler) {
        this.handler = handler;
    }

    /**
     * Return the underlying handler. Use this to pass the environment to
     * {@code EffectRuntime.unsafeRunWithHandler} or to {@code EffectWithEnv.run}.
     */
    public CapabilityHandler<Capability<?>> toHandler() {
        return handler;
    }

    /**
     * Create an environment for a single capability family using an F-bounded factory.
     *
     * <p>The F-bound {@code C extends Capability<R>} ensures that {@code handler} returns
     * exactly the type {@code R} that the capability declares — the compiler rejects handlers
     * that return the wrong type.
     *
     * <pre>{@code
     * // StoreOps extends Capability<String> — handler must return String
     * HandlerEnv<StoreOps> env = HandlerEnv.of(StoreOps.class, cap -> switch (cap) {
     *     case StoreOps.Get g -> store.get(g.key());
     *     case StoreOps.Put p -> { store.put(p.key(), p.value()); yield "ok"; }
     * });
     *
     * // Compile error — int is not String:
     * // HandlerEnv.of(StoreOps.class, cap -> 42);
     * }</pre>
     *
     * @param type    the capability family class (exact type, not a supertype)
     * @param handler lambda dispatching on the capability's concrete subtypes
     */
    public static <R, C extends Capability<R>> HandlerEnv<C> of(
            Class<C> type,
            ThrowingFunction<C, R> handler
    ) {
        return new HandlerEnv<>(CapabilityHandler.builder().on(type, handler).build());
    }

    /**
     * Combine this environment with {@code other}, producing an environment
     * that covers both {@code R} and {@code S}.
     *
     * <p>This environment is tried first; {@code other} is the fallback.
     * The phantom type {@code With<R, S>} tracks the combination at compile time.
     *
     * <pre>{@code
     * HandlerEnv<With<StoreOps, LogOps>> full = storeEnv.and(logEnv);
     * }</pre>
     */
    public <S> HandlerEnv<With<R, S>> and(HandlerEnv<S> other) {
        return new HandlerEnv<>(this.handler.orElse(other.handler));
    }

    /**
     * Wrap an existing {@link CapabilityHandler} with a declared phantom type.
     * Use when migrating from an untyped handler or when the F-bound on {@link #of}
     * cannot be satisfied (e.g., capability families with mixed return types).
     *
     * <p>The caller is responsible for ensuring that {@code handler} actually covers
     * the capabilities implied by {@code R}.
     */
    @SuppressWarnings("unchecked")
    public static <R> HandlerEnv<R> fromHandler(CapabilityHandler<Capability<?>> handler) {
        return (HandlerEnv<R>) new HandlerEnv<>(handler);
    }

    /**
     * An empty environment covering no capabilities.
     * Use with effects that have {@link Empty} requirements.
     *
     * <pre>{@code
     * HandlerEnv<Empty> env = HandlerEnv.empty();
     * }</pre>
     */
    public static HandlerEnv<Empty> empty() {
        return new HandlerEnv<>(cap -> {
            throw new UnsupportedOperationException(
                    "No handler registered for capability: " + cap.getClass().getName());
        });
    }
}
```

**After Task 3, compile check:**
```bash
cd /Users/pradeep.samuel/roux && ./gradlew :lib:compileJava 2>&1 | tail -20
```

**After Task 3, commit all three files together:**
```
feat(07-1): add Empty, With phantom types and HandlerEnv
```
Stage individually:
- `lib/src/main/java/com/cajunsystems/roux/capability/Empty.java`
- `lib/src/main/java/com/cajunsystems/roux/capability/With.java`
- `lib/src/main/java/com/cajunsystems/roux/capability/HandlerEnv.java`

### Task 4 — Create `HandlerEnvTest`

Create `lib/src/test/java/com/cajunsystems/roux/capability/HandlerEnvTest.java`:

```java
package com.cajunsystems.roux.capability;

import com.cajunsystems.roux.data.Unit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HandlerEnvTest {

    // -----------------------------------------------------------------------
    // Test capability families (class-level — sealed interfaces cannot be local)
    // -----------------------------------------------------------------------

    sealed interface StoreOps extends Capability<String> {
        record Get(String key)                    implements StoreOps {}
        record Put(String key, String value)      implements StoreOps {}
    }

    sealed interface LogOps extends Capability<Unit> {
        record Info(String message)  implements LogOps {}
        record Error(String message) implements LogOps {}
    }

    sealed interface CountOps extends Capability<Integer> {
        record Increment() implements CountOps {}
        record Get()       implements CountOps {}
    }

    // -----------------------------------------------------------------------
    // HandlerEnv.empty()
    // -----------------------------------------------------------------------

    @Test
    void empty_throwsForAnyCapability() {
        HandlerEnv<Empty> env = HandlerEnv.empty();
        assertThrows(UnsupportedOperationException.class, () ->
                env.toHandler().handle(new StoreOps.Get("k")));
    }

    // -----------------------------------------------------------------------
    // HandlerEnv.of() — single capability family
    // -----------------------------------------------------------------------

    @Test
    void of_dispatchesGetToHandler() throws Exception {
        Map<String, String> store = new HashMap<>();
        store.put("user", "Alice");

        HandlerEnv<StoreOps> env = HandlerEnv.of(StoreOps.class, cap -> switch (cap) {
            case StoreOps.Get g -> store.getOrDefault(g.key(), "missing");
            case StoreOps.Put p -> { store.put(p.key(), p.value()); yield "ok"; }
        });

        String result = env.toHandler().handle(new StoreOps.Get("user"));
        assertEquals("Alice", result);
    }

    @Test
    void of_dispatchesPutToHandler() throws Exception {
        Map<String, String> store = new HashMap<>();

        HandlerEnv<StoreOps> env = HandlerEnv.of(StoreOps.class, cap -> switch (cap) {
            case StoreOps.Get g -> store.getOrDefault(g.key(), "missing");
            case StoreOps.Put p -> { store.put(p.key(), p.value()); yield "ok"; }
        });

        String result = env.toHandler().handle(new StoreOps.Put("k", "v"));
        assertEquals("ok", result);
        assertEquals("v", store.get("k"));
    }

    @Test
    void of_throwsForUnregisteredCapability() {
        HandlerEnv<StoreOps> env = HandlerEnv.of(StoreOps.class, cap -> switch (cap) {
            case StoreOps.Get g -> "value";
            case StoreOps.Put p -> "ok";
        });

        // LogOps.Info is not registered in storeEnv
        assertThrows(UnsupportedOperationException.class, () ->
                env.toHandler().handle(new LogOps.Info("hello")));
    }

    // -----------------------------------------------------------------------
    // HandlerEnv.and() — combine two environments
    // -----------------------------------------------------------------------

    @Test
    void and_combinesStoreAndLog() throws Exception {
        List<String> log = new ArrayList<>();
        Map<String, String> store = new HashMap<>();
        store.put("name", "Bob");

        HandlerEnv<StoreOps> storeEnv = HandlerEnv.of(StoreOps.class, cap -> switch (cap) {
            case StoreOps.Get g -> store.getOrDefault(g.key(), "missing");
            case StoreOps.Put p -> { store.put(p.key(), p.value()); yield "ok"; }
        });

        HandlerEnv<LogOps> logEnv = HandlerEnv.of(LogOps.class, cap -> switch (cap) {
            case LogOps.Info  m -> { log.add("INFO: "  + m.message()); yield Unit.unit(); }
            case LogOps.Error m -> { log.add("ERROR: " + m.message()); yield Unit.unit(); }
        });

        HandlerEnv<With<StoreOps, LogOps>> combined = storeEnv.and(logEnv);

        String name = combined.toHandler().handle(new StoreOps.Get("name"));
        combined.toHandler().handle(new LogOps.Info("fetched " + name));

        assertEquals("Bob", name);
        assertEquals(List.of("INFO: fetched Bob"), log);
    }

    @Test
    void and_throwsForUnregisteredCapability() {
        HandlerEnv<StoreOps> storeEnv = HandlerEnv.of(StoreOps.class, cap -> switch (cap) {
            case StoreOps.Get g -> "value";
            case StoreOps.Put p -> "ok";
        });
        HandlerEnv<LogOps> logEnv = HandlerEnv.of(LogOps.class, cap -> switch (cap) {
            case LogOps.Info  m -> Unit.unit();
            case LogOps.Error m -> Unit.unit();
        });

        HandlerEnv<With<StoreOps, LogOps>> combined = storeEnv.and(logEnv);

        // CountOps not registered
        assertThrows(UnsupportedOperationException.class, () ->
                combined.toHandler().handle(new CountOps.Get()));
    }

    @Test
    void and_isChainable_threeCapabilities() throws Exception {
        int[] counter = {0};

        HandlerEnv<StoreOps>  storeEnv = HandlerEnv.of(StoreOps.class,  cap -> "value");
        HandlerEnv<LogOps>    logEnv   = HandlerEnv.of(LogOps.class,    cap -> Unit.unit());
        HandlerEnv<CountOps>  countEnv = HandlerEnv.of(CountOps.class,  cap -> switch (cap) {
            case CountOps.Increment i -> ++counter[0];
            case CountOps.Get g       -> counter[0];
        });

        // With<StoreOps, With<LogOps, CountOps>> — right-nested by convention
        HandlerEnv<With<StoreOps, With<LogOps, CountOps>>> full =
                storeEnv.and(logEnv.and(countEnv));

        full.toHandler().handle(new CountOps.Increment());
        full.toHandler().handle(new CountOps.Increment());
        int count = full.toHandler().handle(new CountOps.Get());

        assertEquals(2, count);
    }

    // -----------------------------------------------------------------------
    // HandlerEnv.fromHandler() — escape hatch
    // -----------------------------------------------------------------------

    @Test
    void fromHandler_wrapsExistingHandler() throws Exception {
        CapabilityHandler<Capability<?>> legacy = CapabilityHandler.builder()
                .on(StoreOps.Get.class,  c -> "legacy-" + c.key())
                .on(StoreOps.Put.class,  c -> "ok")
                .build();

        HandlerEnv<StoreOps> env = HandlerEnv.fromHandler(legacy);

        String result = env.toHandler().handle(new StoreOps.Get("x"));
        assertEquals("legacy-x", result);
    }
}
```

**After Task 4, commit:**
```
test(07-1): add HandlerEnvTest with 9 tests
```
Stage: `lib/src/test/java/com/cajunsystems/roux/capability/HandlerEnvTest.java`

### Task 5 — Run all tests

```bash
cd /Users/pradeep.samuel/roux
./gradlew :lib:test --tests "com.cajunsystems.roux.capability.HandlerEnvTest" 2>&1 | tail -30
```

Then full suite:
```bash
./gradlew :lib:test 2>&1 | tail -20
```

All tests must pass. No regressions in existing tests.

## Verification

- [ ] `Empty.java` — phantom interface, no methods, no instances
- [ ] `With<A, B>.java` — two-param phantom interface, no methods, no instances
- [ ] `HandlerEnv<R>.java` — final class, private constructor, four static factories
- [ ] `HandlerEnv.of()` uses F-bound `<R, C extends Capability<R>>`
- [ ] `HandlerEnv.and()` returns `HandlerEnv<With<R, S>>`
- [ ] `HandlerEnv.empty()` returns `HandlerEnv<Empty>`
- [ ] `HandlerEnv.fromHandler()` wraps existing handler
- [ ] `HandlerEnvTest` passes 9 tests
- [ ] Full test suite passes (no regression)

## Success Criteria

- `HandlerEnv.of(StoreOps.class, ...)` returns `HandlerEnv<StoreOps>` — phantom type is the capability family
- `storeEnv.and(logEnv)` returns `HandlerEnv<With<StoreOps, LogOps>>` — both families tracked
- Three-way `.and()` chains correctly: `storeEnv.and(logEnv.and(countEnv))` handles all three capability types
- Unregistered capability throws `UnsupportedOperationException`
- `HandlerEnv.empty()` throws for any capability (correct base case)
- Zero new production dependencies

## Output

Production:
- `lib/src/main/java/com/cajunsystems/roux/capability/Empty.java`
- `lib/src/main/java/com/cajunsystems/roux/capability/With.java`
- `lib/src/main/java/com/cajunsystems/roux/capability/HandlerEnv.java`

Test:
- `lib/src/test/java/com/cajunsystems/roux/capability/HandlerEnvTest.java`
