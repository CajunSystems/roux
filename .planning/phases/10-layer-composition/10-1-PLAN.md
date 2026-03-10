# Phase 10, Plan 1: Layer Composition

## objective
Add horizontal (`and`) and vertical (`andProvide`) composition operators to `Layer`, enabling multiple layers to be combined into a single layer that satisfies all requirements.

---

## execution_context
- `lib/src/main/java/com/cajunsystems/roux/capability/Layer.java`
- `lib/src/main/java/com/cajunsystems/roux/capability/HandlerEnv.java`
- `lib/src/main/java/com/cajunsystems/roux/capability/With.java`
- `lib/src/test/java/com/cajunsystems/roux/capability/LayerCompositionTest.java` (to be created)

---

## context

**Layer.java current state:**
`Layer<RIn, E, ROut>` is a `@FunctionalInterface` with:
- `build(HandlerEnv<RIn>) → Effect<E, HandlerEnv<ROut>>` — the SAM
- `Layer.succeed(type, handler)` — leaf layer returning `Layer<Empty, RuntimeException, C>`
- `Layer.fromEffect(type, effectFn)` — layer with dependencies

**HandlerEnv.and():**
`<S> HandlerEnv<With<R, S>> and(HandlerEnv<S> other)` — merges two envs at runtime, returns phantom `With<R,S>`

**Error type challenge:**
`and()` takes `Layer<RIn, E2, S>` where `E2` may differ from `this` layer's `E`. The combined layer must have a common error type. Solution: widen both to `Throwable` using an unchecked cast helper. The combined layer error type is always `Throwable`.

**@FunctionalInterface and private methods:**
Java 9+ allows `private static` methods in interfaces without breaking the functional interface contract.

---

## tasks

### Task 1: Add `widenError` private helper to `Layer`

Add a private static helper that widens an effect's error type via unchecked cast. This is safe because `Ex extends Throwable` and Java's type erasure means the cast is a no-op at runtime.

**File:** `lib/src/main/java/com/cajunsystems/roux/capability/Layer.java`

Add after the `fromEffect` method (before the closing `}`):

```java
// -----------------------------------------------------------------------
// Composition operators
// -----------------------------------------------------------------------

@SuppressWarnings("unchecked")
private static <Ex extends Throwable, A> Effect<Throwable, A> widenError(Effect<Ex, A> effect) {
    return (Effect<Throwable, A>) effect;
}
```

Commit: `feat(10-1): add widenError helper to Layer`

---

### Task 2: Add `and()` — horizontal composition

Both layers share the same input `HandlerEnv<RIn>`. Run both builds against that same env, then merge their outputs with `h1.and(h2)`.

**File:** `lib/src/main/java/com/cajunsystems/roux/capability/Layer.java`

Add after the `widenError` helper:

```java
/**
 * Horizontal composition: both layers share the same input, outputs are merged.
 *
 * <pre>{@code
 * Layer<Empty, Throwable, With<DbOps, AuditOps>> appLayer = dbLayer.and(auditLayer);
 * HandlerEnv<With<DbOps, AuditOps>> env =
 *     runtime.unsafeRun(appLayer.build(HandlerEnv.empty()));
 * }</pre>
 *
 * <p>The combined layer's error type is {@code Throwable} — both individual error
 * types are widened to their common supertype.
 *
 * @param other the other layer to compose with (same input, different output)
 * @param <E2>  the error type of the other layer
 * @param <S>   the capability type produced by the other layer
 * @return a layer that builds both and merges their environments
 */
default <E2 extends Throwable, S> Layer<RIn, Throwable, With<ROut, S>> and(Layer<RIn, E2, S> other) {
    return env -> widenError(this.build(env))
            .flatMap(h1 -> widenError(other.build(env)).map(h2 -> h1.and(h2)));
}
```

Commit: `feat(10-1): add Layer.and() horizontal composition`

---

### Task 3: Add `andProvide()` — vertical composition

`this` layer's output (`HandlerEnv<ROut>`) becomes `next` layer's input. Both outputs are retained in the result via `h1.and(h2)`.

```
this:     RIn  →  ROut
next:     ROut →  S
combined: RIn  →  With<ROut, S>
```

**File:** `lib/src/main/java/com/cajunsystems/roux/capability/Layer.java`

Add after `and()`:

```java
/**
 * Vertical composition: this layer's output feeds the next layer's input.
 * Both outputs are retained.
 *
 * <pre>{@code
 * // configLayer : Empty     → ConfigOps
 * // emailLayer  : ConfigOps → EmailOps
 * // combined    : Empty     → With<ConfigOps, EmailOps>
 * Layer<Empty, Throwable, With<ConfigOps, EmailOps>> combined =
 *     configLayer.andProvide(emailLayer);
 * }</pre>
 *
 * <p>The combined layer's error type is {@code Throwable} — both individual error
 * types are widened to their common supertype.
 *
 * @param next the downstream layer that depends on this layer's output
 * @param <E2> the error type of the downstream layer
 * @param <S>  the capability type produced by the downstream layer
 * @return a layer that builds both in sequence, retaining both environments
 */
default <E2 extends Throwable, S> Layer<RIn, Throwable, With<ROut, S>> andProvide(Layer<ROut, E2, S> next) {
    return env -> widenError(this.build(env))
            .flatMap(h1 -> widenError(next.build(h1)).map(h2 -> h1.and(h2)));
}
```

Commit: `feat(10-1): add Layer.andProvide() vertical composition`

---

### Task 4: Write `LayerCompositionTest.java`

**File:** `lib/src/test/java/com/cajunsystems/roux/capability/LayerCompositionTest.java`

Test all three composition scenarios:

```java
package com.cajunsystems.roux.capability;

import com.cajunsystems.roux.EffectRuntime;
import com.cajunsystems.roux.runtime.DefaultEffectRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LayerCompositionTest {

    // Capability families
    sealed interface StoreOps extends Capability<String> {
        record Get(String key) implements StoreOps {}
        record Put(String key, String value) implements StoreOps {}
    }

    sealed interface LogOps extends Capability<String> {
        record Info(String message) implements LogOps {}
    }

    sealed interface ConfigOps extends Capability<String> {
        record GetConfig(String key) implements ConfigOps {}
    }

    sealed interface EmailOps extends Capability<String> {
        record Send(String to, String body) implements EmailOps {}
    }

    private Map<String, String> store;
    private java.util.List<String> logSink;
    private Map<String, String> config;
    private EffectRuntime runtime;

    @BeforeEach
    void setUp() {
        store = new HashMap<>();
        logSink = new java.util.ArrayList<>();
        config = new HashMap<>();
        config.put("email.host", "smtp.example.com");
        runtime = new DefaultEffectRuntime();
    }

    Layer<Empty, RuntimeException, StoreOps> storeLayer() {
        return Layer.succeed(StoreOps.class, cap -> switch (cap) {
            case StoreOps.Get g -> store.getOrDefault(g.key(), "missing");
            case StoreOps.Put p -> { store.put(p.key(), p.value()); yield "ok"; }
        });
    }

    Layer<Empty, RuntimeException, LogOps> logLayer() {
        return Layer.succeed(LogOps.class, cap -> switch (cap) {
            case LogOps.Info m -> { logSink.add(m.message()); yield "logged"; }
        });
    }

    // --- and() tests ---

    @Test
    void and_buildsBothLeafLayers() throws Throwable {
        Layer<Empty, Throwable, With<StoreOps, LogOps>> appLayer = storeLayer().and(logLayer());

        HandlerEnv<With<StoreOps, LogOps>> env =
                runtime.unsafeRun(appLayer.build(HandlerEnv.empty()));

        // Can dispatch both capabilities through the combined env
        String putResult = env.toHandler().handle(new StoreOps.Put("k", "v"));
        assertEquals("ok", putResult);

        String getResult = env.toHandler().handle(new StoreOps.Get("k"));
        assertEquals("v", getResult);

        String logResult = env.toHandler().handle(new LogOps.Info("hello"));
        assertEquals("logged", logResult);
        assertEquals(1, logSink.size());
    }

    @Test
    void and_threeLayersViaNesting() throws Throwable {
        Layer<Empty, RuntimeException, ConfigOps> configLayer = Layer.succeed(ConfigOps.class, cap -> switch (cap) {
            case ConfigOps.GetConfig g -> config.getOrDefault(g.key(), "unset");
        });

        // (storeLayer and logLayer) and configLayer
        Layer<Empty, Throwable, With<With<StoreOps, LogOps>, ConfigOps>> triLayer =
                storeLayer().and(logLayer()).and(configLayer);

        HandlerEnv<With<With<StoreOps, LogOps>, ConfigOps>> env =
                runtime.unsafeRun(triLayer.build(HandlerEnv.empty()));

        assertEquals("ok", env.toHandler().handle(new StoreOps.Put("x", "42")));
        assertEquals("42", env.toHandler().handle(new StoreOps.Get("x")));
        assertEquals("logged", env.toHandler().handle(new LogOps.Info("test")));
        assertEquals("smtp.example.com", env.toHandler().handle(new ConfigOps.GetConfig("email.host")));
    }

    // --- andProvide() tests ---

    @Test
    void andProvide_configFeedsEmailLayer() throws Throwable {
        // configLayer: Empty → ConfigOps (leaf)
        Layer<Empty, RuntimeException, ConfigOps> configLayer = Layer.succeed(ConfigOps.class, cap -> switch (cap) {
            case ConfigOps.GetConfig g -> config.getOrDefault(g.key(), "unset");
        });

        // emailLayer: ConfigOps → EmailOps (reads config during construction)
        java.util.List<String> sentEmails = new java.util.ArrayList<>();
        Layer<ConfigOps, Exception, EmailOps> emailLayer = Layer.fromEffect(
                EmailOps.class,
                configEnv -> com.cajunsystems.roux.Effect.suspend(() -> {
                    String host = configEnv.toHandler().handle(new ConfigOps.GetConfig("email.host"));
                    return (EmailOps cap) -> switch (cap) {
                        case EmailOps.Send s -> {
                            sentEmails.add(host + ":" + s.to() + ":" + s.body());
                            yield "sent";
                        }
                    };
                })
        );

        // combined: Empty → With<ConfigOps, EmailOps>
        Layer<Empty, Throwable, With<ConfigOps, EmailOps>> combined = configLayer.andProvide(emailLayer);

        HandlerEnv<With<ConfigOps, EmailOps>> env =
                runtime.unsafeRun(combined.build(HandlerEnv.empty()));

        // EmailOps works and used the config value during construction
        String result = env.toHandler().handle(new EmailOps.Send("alice@example.com", "hello"));
        assertEquals("sent", result);
        assertEquals(1, sentEmails.size());
        assertEquals("smtp.example.com:alice@example.com:hello", sentEmails.get(0));

        // ConfigOps is also available in the combined env
        String host = env.toHandler().handle(new ConfigOps.GetConfig("email.host"));
        assertEquals("smtp.example.com", host);
    }

    @Test
    void andProvide_retainsBothOutputs() throws Throwable {
        // Verify that andProvide retains BOTH this.output and next.output
        Layer<Empty, RuntimeException, ConfigOps> configLayer = Layer.succeed(ConfigOps.class, cap -> switch (cap) {
            case ConfigOps.GetConfig g -> config.getOrDefault(g.key(), "unset");
        });

        Layer<ConfigOps, RuntimeException, LogOps> logFromConfigLayer = Layer.fromEffect(
                LogOps.class,
                configEnv -> com.cajunsystems.roux.Effect.suspend(() -> {
                    // Reads config to determine log prefix
                    String prefix = configEnv.toHandler().handle(new ConfigOps.GetConfig("log.prefix"));
                    return (LogOps cap) -> switch (cap) {
                        case LogOps.Info m -> { logSink.add(prefix + m.message()); yield "ok"; }
                    };
                })
        );

        config.put("log.prefix", "[INFO] ");
        Layer<Empty, Throwable, With<ConfigOps, LogOps>> combined = configLayer.andProvide(logFromConfigLayer);
        HandlerEnv<With<ConfigOps, LogOps>> env =
                runtime.unsafeRun(combined.build(HandlerEnv.empty()));

        // Both capabilities available
        env.toHandler().handle(new LogOps.Info("message"));
        assertEquals("[INFO] message", logSink.get(0));

        String configVal = env.toHandler().handle(new ConfigOps.GetConfig("log.prefix"));
        assertEquals("[INFO] ", configVal);
    }
}
```

Commit: `test(10-1): LayerCompositionTest for and() and andProvide()`

---

## verification

```bash
./gradlew :lib:test --tests "com.cajunsystems.roux.capability.LayerCompositionTest"
./gradlew :lib:test
```

All tests green.

---

## success_criteria
- [ ] `Layer.widenError()` private helper added
- [ ] `Layer.and()` default method added with correct return type `Layer<RIn, Throwable, With<ROut, S>>`
- [ ] `Layer.andProvide()` default method added with correct return type `Layer<RIn, Throwable, With<ROut, S>>`
- [ ] `LayerCompositionTest` created with 4 tests
- [ ] All tests pass
- [ ] No regressions in existing test suite

---

## output
- Modified: `lib/src/main/java/com/cajunsystems/roux/capability/Layer.java`
- Created: `lib/src/test/java/com/cajunsystems/roux/capability/LayerCompositionTest.java`
