# Plan: 09-1 — Layer Core

## Objective
Implement `Layer<RIn, E, ROut>` — a functional interface whose single abstract method
`build(HandlerEnv<RIn>)` returns `Effect<E, HandlerEnv<ROut>>`. Provides two factory methods:
`succeed()` for leaf layers with no dependencies, and `fromEffect()` for layers whose handler
construction requires reading from an input environment via effectful computation.

## Context

**Package:** `com.cajunsystems.roux.capability` — alongside `HandlerEnv`, `Empty`, `With`.

**Key existing types:**
- `com.cajunsystems.roux.Effect<E,A>` — `.succeed(A)`, `.suspend(ThrowingSupplier<A>)`, `.map(Function<A,B>)`
- `com.cajunsystems.roux.data.ThrowingFunction<A,B>` — `B apply(A) throws Exception`
- `com.cajunsystems.roux.data.ThrowingSupplier<A>` — `A get() throws Exception`
- `com.cajunsystems.roux.capability.HandlerEnv<R>` — `.of(Class<C>, ThrowingFunction<C,R>)`, `.toHandler()`
- `com.cajunsystems.roux.capability.Empty` — phantom type (no capabilities)

**Design:**

`Layer<RIn, E, ROut>` is a `@FunctionalInterface` — `build` is the SAM:
```java
Effect<E, HandlerEnv<ROut>> build(HandlerEnv<RIn> env);
```

**`Layer.succeed()`** — leaf layer, no input dependencies:
```java
static <R, C extends Capability<R>> Layer<Empty, RuntimeException, C> succeed(
        Class<C> type, ThrowingFunction<C, R> handler) {
    return env -> Effect.succeed(HandlerEnv.of(type, handler));
}
```
The input `env` is `HandlerEnv<Empty>` — always ignored. Returns `Effect.succeed(...)` which
can be typed as `Effect<RuntimeException, HandlerEnv<C>>`.

**`Layer.fromEffect()`** — layer whose handler is produced by an Effect:
```java
static <RIn, E extends Throwable, R, C extends Capability<R>> Layer<RIn, E, C> fromEffect(
        Class<C> type,
        Function<HandlerEnv<RIn>, Effect<E, ThrowingFunction<C, R>>> effectFn) {
    return env -> effectFn.apply(env).map(h -> HandlerEnv.of(type, h));
}
```
The function receives the input `HandlerEnv<RIn>` during construction — it can call
`env.toHandler().handle(capability)` to read configuration from the environment.
The effect resolves to a `ThrowingFunction<C, R>` (the handler), which is wrapped into
a `HandlerEnv<C>` via `HandlerEnv.of(type, h)`.

**Usage pattern:**
```java
// 1. Define a leaf layer
Layer<Empty, RuntimeException, StoreOps> storeLayer =
    Layer.succeed(StoreOps.class, cap -> switch (cap) {
        case StoreOps.Get g -> store.get(g.key());
        case StoreOps.Put p -> { store.put(p.key(), p.value()); yield "ok"; }
    });

// 2. Build it — get back an Effect<RuntimeException, HandlerEnv<StoreOps>>
HandlerEnv<StoreOps> env = runtime.unsafeRun(storeLayer.build(HandlerEnv.empty()));

// 3. Use the env with EffectWithEnv
EffectWithEnv.<StoreOps, Throwable, String>of(new StoreOps.Get("k").toEffect())
    .run(env, runtime);
```

**`fromEffect` with input env access:**
```java
// ConfigOps provides configuration strings
Layer<ConfigOps, Throwable, EmailOps> emailLayer = Layer.fromEffect(
    EmailOps.class,
    env -> Effect.suspend(() -> {
        String host = env.toHandler().handle(new ConfigOps.Get("smtp.host"));
        ThrowingFunction<EmailOps, String> handler = cap -> switch (cap) {
            case EmailOps.Send s -> sendViaSmtp(host, s.to());
        };
        return handler;
    })
);
```

**Type inference notes:**
- Inside `fromEffect`'s `build` impl: `HandlerEnv.of(type, h)` — Java can infer the F-bound
  since `type` is `Class<C>` and `h` is `ThrowingFunction<C, R>`. If inference fails, use
  explicit witness: `HandlerEnv.<R, C>of(type, h)` — or assign `h` to a typed local variable.
- Inside `succeed`: `Effect.succeed(HandlerEnv.of(type, handler))` — `E` is inferred as
  `RuntimeException` from the target type `Layer<Empty, RuntimeException, C>`.

**Tests:** `LayerTest` in `com.cajunsystems.roux.capability`. Capability types as class-level
nested sealed interfaces. Uses `DefaultEffectRuntime` to run effects during build.

## Tasks

### Task 1 — Create `Layer`

Create `lib/src/main/java/com/cajunsystems/roux/capability/Layer.java`:

```java
package com.cajunsystems.roux.capability;

import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.data.ThrowingFunction;

import java.util.function.Function;

/**
 * A recipe that, given a {@link HandlerEnv}{@code <RIn>}, builds a
 * {@link HandlerEnv}{@code <ROut>}, possibly performing effects during construction.
 *
 * <p>This is the direct analog of {@code ZLayer[RIn, E, ROut]} from ZIO.
 *
 * <h3>Leaf layer (no dependencies)</h3>
 * <pre>{@code
 * Layer<Empty, RuntimeException, StoreOps> storeLayer =
 *     Layer.succeed(StoreOps.class, cap -> switch (cap) {
 *         case StoreOps.Get g -> store.get(g.key());
 *         case StoreOps.Put p -> { store.put(p.key(), p.value()); yield "ok"; }
 *     });
 *
 * HandlerEnv<StoreOps> env = runtime.unsafeRun(storeLayer.build(HandlerEnv.empty()));
 * }</pre>
 *
 * <h3>Layer with dependencies</h3>
 * <pre>{@code
 * // emailLayer reads smtp host from configEnv during construction
 * Layer<ConfigOps, Exception, EmailOps> emailLayer = Layer.fromEffect(
 *     EmailOps.class,
 *     env -> Effect.suspend(() -> {
 *         String host = env.toHandler().handle(new ConfigOps.Get("smtp.host"));
 *         ThrowingFunction<EmailOps, String> handler = cap -> switch (cap) {
 *             case EmailOps.Send s -> sendVia(host, s.to());
 *         };
 *         return handler;
 *     })
 * );
 * }</pre>
 *
 * <h3>Building and using</h3>
 * <pre>{@code
 * // build() returns an Effect — run it to materialise the HandlerEnv
 * HandlerEnv<EmailOps> emailEnv = runtime.unsafeRun(emailLayer.build(configEnv));
 * }</pre>
 *
 * @param <RIn>  the capability environment this layer requires as input
 * @param <E>    the error type that may occur during layer construction
 * @param <ROut> the capability environment this layer produces
 */
@FunctionalInterface
public interface Layer<RIn, E extends Throwable, ROut> {

    /**
     * Build this layer: given the input environment, produce the output environment
     * as an {@link Effect}.
     *
     * <p>The returned effect may perform IO during construction — e.g., reading config,
     * establishing connections, or allocating resources.
     *
     * @param env the input capability environment
     * @return an effect that, when run, produces the output {@link HandlerEnv}
     */
    Effect<E, HandlerEnv<ROut>> build(HandlerEnv<RIn> env);

    // -----------------------------------------------------------------------
    // Factory methods
    // -----------------------------------------------------------------------

    /**
     * Create a leaf layer that requires no dependencies.
     *
     * <p>Uses the same F-bound as {@link HandlerEnv#of}: {@code C extends Capability<R>}
     * ensures the handler returns exactly the type {@code R} declared by the capability.
     *
     * <pre>{@code
     * Layer<Empty, RuntimeException, StoreOps> layer =
     *     Layer.succeed(StoreOps.class, cap -> switch (cap) {
     *         case StoreOps.Get g -> store.get(g.key());
     *         case StoreOps.Put p -> { store.put(p.key(), p.value()); yield "ok"; }
     *     });
     * }</pre>
     *
     * @param type    the capability family class
     * @param handler handler lambda for all subtypes of the capability family
     */
    static <R, C extends Capability<R>> Layer<Empty, RuntimeException, C> succeed(
            Class<C> type,
            ThrowingFunction<C, R> handler
    ) {
        return env -> Effect.succeed(HandlerEnv.of(type, handler));
    }

    /**
     * Create a layer whose handler is produced by running an {@link Effect}.
     *
     * <p>The {@code effectFn} receives the input {@link HandlerEnv}{@code <RIn>} and
     * returns an effect that produces the handler. This lets the layer read from its
     * input environment during construction:
     *
     * <pre>{@code
     * Layer<ConfigOps, Exception, EmailOps> emailLayer = Layer.fromEffect(
     *     EmailOps.class,
     *     env -> Effect.suspend(() -> {
     *         String host = env.toHandler().handle(new ConfigOps.Get("smtp.host"));
     *         ThrowingFunction<EmailOps, String> handler = cap -> switch (cap) {
     *             case EmailOps.Send s -> sendVia(host, s.to());
     *         };
     *         return handler;
     *     })
     * );
     * }</pre>
     *
     * @param type     the capability family class
     * @param effectFn function from input env to an effect producing the handler
     */
    static <RIn, E extends Throwable, R, C extends Capability<R>> Layer<RIn, E, C> fromEffect(
            Class<C> type,
            Function<HandlerEnv<RIn>, Effect<E, ThrowingFunction<C, R>>> effectFn
    ) {
        return env -> effectFn.apply(env).map(h -> HandlerEnv.of(type, h));
    }
}
```

**After writing, compile check:**
```bash
cd /Users/pradeep.samuel/roux && ./gradlew :lib:compileJava 2>&1 | tail -20
```

Fix any compile errors. Common issues:
- `HandlerEnv.of(type, h)` inside `fromEffect` lambda may need type witness `HandlerEnv.<R, C>of(type, h)` or a typed local variable
- `Effect.succeed(...)` in `succeed` may need `Effect.<RuntimeException, HandlerEnv<C>>succeed(...)` if inference fails

**Commit:**
```
feat(09-1): add Layer functional interface with succeed and fromEffect factories
```
Stage: `lib/src/main/java/com/cajunsystems/roux/capability/Layer.java`

### Task 2 — Create `LayerTest`

Create `lib/src/test/java/com/cajunsystems/roux/capability/LayerTest.java`:

```java
package com.cajunsystems.roux.capability;

import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.data.ThrowingFunction;
import com.cajunsystems.roux.data.Unit;
import com.cajunsystems.roux.runtime.DefaultEffectRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LayerTest {

    // -----------------------------------------------------------------------
    // Test capability families
    // -----------------------------------------------------------------------

    sealed interface StoreOps extends Capability<String> {
        record Get(String key)               implements StoreOps {}
        record Put(String key, String value) implements StoreOps {}
    }

    sealed interface ConfigOps extends Capability<String> {
        record Get(String key) implements ConfigOps {}
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
    // Layer.succeed() — leaf layer, no dependencies
    // -----------------------------------------------------------------------

    @Test
    void succeed_buildReturnsHandlerEnv() throws Throwable {
        Map<String, String> store = new HashMap<>();
        store.put("greeting", "hello");

        Layer<Empty, RuntimeException, StoreOps> layer = Layer.succeed(StoreOps.class, cap -> switch (cap) {
            case StoreOps.Get g -> store.getOrDefault(g.key(), "missing");
            case StoreOps.Put p -> { store.put(p.key(), p.value()); yield "ok"; }
        });

        // build() returns Effect<RuntimeException, HandlerEnv<StoreOps>>
        HandlerEnv<StoreOps> env = runtime.unsafeRun(layer.build(HandlerEnv.empty()));

        String result = env.toHandler().handle(new StoreOps.Get("greeting"));
        assertEquals("hello", result);
    }

    @Test
    void succeed_buildPutDispatchesCorrectly() throws Throwable {
        Map<String, String> store = new HashMap<>();

        Layer<Empty, RuntimeException, StoreOps> layer = Layer.succeed(StoreOps.class, cap -> switch (cap) {
            case StoreOps.Get g -> store.getOrDefault(g.key(), "missing");
            case StoreOps.Put p -> { store.put(p.key(), p.value()); yield "ok"; }
        });

        HandlerEnv<StoreOps> env = runtime.unsafeRun(layer.build(HandlerEnv.empty()));
        env.toHandler().handle(new StoreOps.Put("k", "v"));

        assertEquals("v", store.get("k"));
    }

    @Test
    void succeed_buildIsIdempotent() throws Throwable {
        // Calling build() twice produces independent HandlerEnvs backed by the same handler
        Layer<Empty, RuntimeException, StoreOps> layer = Layer.succeed(StoreOps.class, cap -> "constant");

        HandlerEnv<StoreOps> env1 = runtime.unsafeRun(layer.build(HandlerEnv.empty()));
        HandlerEnv<StoreOps> env2 = runtime.unsafeRun(layer.build(HandlerEnv.empty()));

        assertEquals("constant", env1.toHandler().handle(new StoreOps.Get("x")));
        assertEquals("constant", env2.toHandler().handle(new StoreOps.Get("y")));
    }

    // -----------------------------------------------------------------------
    // Layer.fromEffect() — effectful handler construction
    // -----------------------------------------------------------------------

    @Test
    void fromEffect_buildsHandlerFromSucceedEffect() throws Throwable {
        // fromEffect where the effect is already determined (no input env needed)
        Layer<Empty, Throwable, StoreOps> layer = Layer.fromEffect(
                StoreOps.class,
                env -> {
                    ThrowingFunction<StoreOps, String> handler = cap -> switch (cap) {
                        case StoreOps.Get g -> "computed-" + g.key();
                        case StoreOps.Put p -> "ok";
                    };
                    return Effect.succeed(handler);
                }
        );

        HandlerEnv<StoreOps> env = runtime.unsafeRun(layer.build(HandlerEnv.empty()));
        String result = env.toHandler().handle(new StoreOps.Get("name"));
        assertEquals("computed-name", result);
    }

    @Test
    void fromEffect_canReadFromInputEnv() throws Throwable {
        // ConfigOps provides a prefix string used to configure StoreOps
        Map<String, String> config = new HashMap<>();
        config.put("prefix", "v2");

        HandlerEnv<ConfigOps> configEnv = HandlerEnv.of(ConfigOps.class, cap -> switch (cap) {
            case ConfigOps.Get g -> config.getOrDefault(g.key(), "default");
        });

        // StoreOps layer reads prefix from ConfigOps during construction
        Layer<ConfigOps, Throwable, StoreOps> storeLayer = Layer.fromEffect(
                StoreOps.class,
                env -> Effect.suspend(() -> {
                    String prefix = env.toHandler().handle(new ConfigOps.Get("prefix"));
                    ThrowingFunction<StoreOps, String> handler = cap -> switch (cap) {
                        case StoreOps.Get g -> prefix + "-" + g.key();
                        case StoreOps.Put p -> "ok";
                    };
                    return handler;
                })
        );

        HandlerEnv<StoreOps> storeEnv = runtime.unsafeRun(storeLayer.build(configEnv));
        String result = storeEnv.toHandler().handle(new StoreOps.Get("item"));
        assertEquals("v2-item", result);
    }

    @Test
    void fromEffect_propagatesFailure() {
        // If the constructing effect fails, build() fails too
        Layer<Empty, Exception, StoreOps> failingLayer = Layer.fromEffect(
                StoreOps.class,
                env -> Effect.fail(new Exception("construction failed"))
        );

        assertThrows(Exception.class, () ->
                runtime.unsafeRun(failingLayer.build(HandlerEnv.empty())));
    }

    // -----------------------------------------------------------------------
    // build() — functional interface directly
    // -----------------------------------------------------------------------

    @Test
    void layer_isAFunctionalInterface_lambdaWorks() throws Throwable {
        // Layer is a @FunctionalInterface — can be expressed as a lambda
        Layer<Empty, RuntimeException, LogOps> logLayer =
                env -> Effect.succeed(HandlerEnv.of(LogOps.class, cap -> Unit.unit()));

        HandlerEnv<LogOps> env = runtime.unsafeRun(logLayer.build(HandlerEnv.empty()));
        assertNotNull(env);
        // Just verify it dispatches without throwing
        env.toHandler().handle(new LogOps.Info("test"));
    }
}
```

**Commit:**
```
test(09-1): add LayerTest with 7 tests
```
Stage: `lib/src/test/java/com/cajunsystems/roux/capability/LayerTest.java`

### Task 3 — Run all tests

```bash
cd /Users/pradeep.samuel/roux
./gradlew :lib:test --tests "com.cajunsystems.roux.capability.LayerTest" 2>&1 | tail -30
```

Then full suite:
```bash
./gradlew :lib:test 2>&1 | tail -20
```

Fix any failures before proceeding.

## Verification

- [ ] `Layer<RIn, E, ROut>` in `com.cajunsystems.roux.capability` package
- [ ] `@FunctionalInterface` — `build()` is the SAM
- [ ] `Layer.succeed()` — F-bounded, returns `Layer<Empty, RuntimeException, C>`
- [ ] `Layer.fromEffect()` — F-bounded, input env accessible during construction
- [ ] `fromEffect` lambda: `env -> effectFn.apply(env).map(h -> HandlerEnv.of(type, h))`
- [ ] `LayerTest` passes 7 tests
- [ ] Full test suite passes (no regression)

## Success Criteria

- `Layer.succeed(StoreOps.class, handler).build(HandlerEnv.empty())` produces `Effect<RuntimeException, HandlerEnv<StoreOps>>`
- Running that effect gives a working `HandlerEnv<StoreOps>` that dispatches capabilities
- `Layer.fromEffect` can read from the input `HandlerEnv<ConfigOps>` via `env.toHandler().handle(...)`
- `fromEffect` failure propagates through `build()`
- `Layer` can be expressed as a lambda (functional interface)
- Zero new production dependencies

## Output

Production:
- `lib/src/main/java/com/cajunsystems/roux/capability/Layer.java`

Test:
- `lib/src/test/java/com/cajunsystems/roux/capability/LayerTest.java`
