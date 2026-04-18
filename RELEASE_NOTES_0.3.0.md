# Roux 0.3.0 — Type-Safe Capability Environments & Virtual-Clock Testing

Roux **0.3.0** introduces compile-time capability tracking via phantom types, a ZIO-style layer system for wiring environments, and a virtual-clock `TestRuntime` for instant, deterministic sleep tests. The `Effect` API is unchanged for the common case — these features layer on top as opt-in additions.

## ✨ Highlights

### ✅ Compile-time capability tracking with `EffectWithEnv`

`EffectWithEnv<R,E,A>` wraps any `Effect<E,A>` and tracks, at compile time, which capabilities it needs via the phantom type `R`. The compiler rejects `run()` calls that don't supply the right environment — no missing-handler surprises at runtime.

```java
// Define a capability
sealed interface StoreOps extends Capability<String> {
    record Get(String key) implements StoreOps {}
    record Put(String key, String value) implements StoreOps {}
}

// Build a typed environment
Map<String, String> store = new HashMap<>();
HandlerEnv<StoreOps> env = HandlerEnv.of(StoreOps.class, cap -> switch (cap) {
    case StoreOps.Get  g -> store.getOrDefault(g.key(), "missing");
    case StoreOps.Put  p -> { store.put(p.key(), p.value()); yield "ok"; }
});

// Wrap an effect — R is inferred as StoreOps
EffectWithEnv<StoreOps, RuntimeException, String> typedEffect =
        EffectWithEnv.of(Effect.from(new StoreOps.Get("greeting")));

// Run — compiler verifies env covers StoreOps
String result = typedEffect.run(env, DefaultEffectRuntime.create());
```

### ✅ Layer system for capability wiring

`Layer<RIn,E,ROut>` is a functional interface that builds a `HandlerEnv<ROut>` from a `HandlerEnv<RIn>`. Layers compose horizontally (`and`) and vertically (`andProvide`), producing a single layer that satisfies multiple requirements.

```java
// Leaf layer — no input requirements
Layer<Empty, RuntimeException, StoreOps> storeLayer =
        Layer.succeed(StoreOps.class, cap -> switch (cap) { ... });

Layer<Empty, RuntimeException, LogOps> logLayer =
        Layer.succeed(LogOps.class, cap -> switch (cap) { ... });

// Horizontal composition — satisfies both StoreOps and LogOps
Layer<Empty, Throwable, With<StoreOps, LogOps>> combined = storeLayer.and(logLayer);

// Build the environment and run
HandlerEnv<With<StoreOps, LogOps>> env =
        combined.build(HandlerEnv.empty()).unsafeRun(runtime);
```

### ✅ Phantom types: `Empty` and `With<A,B>`

`Empty` marks effects that need no capabilities. `With<A,B>` represents a capability environment union — both are compile-time-only interfaces that are never instantiated.

```java
// Effect that needs nothing
EffectWithEnv<Empty, RuntimeException, Integer> pure =
        EffectWithEnv.pure(Effect.succeed(42));
pure.run(HandlerEnv.empty(), runtime); // compiles

// Effect that needs both StoreOps and LogOps
EffectWithEnv<With<StoreOps, LogOps>, IOException, Unit> combined = ...;
combined.run(storeEnv.and(logEnv), runtime); // compiles
// combined.run(storeEnv, runtime);           // compile error — missing LogOps
```

### ✅ First-class `Effect.Sleep` and `TestRuntime`

`Effect.sleep(Duration)` now creates an `Effect.Sleep` node instead of an opaque `Suspend` node. This makes sleep interceptable by custom runtimes. `TestRuntime` uses this to advance a `TestClock` instead of blocking — sleep-dependent effects run instantly in tests.

```java
TestRuntime runtime = TestRuntime.create();

// Runs instantly — no real sleep
runtime.unsafeRun(Effect.sleep(Duration.ofSeconds(30)));

// Virtual clock advanced by 30 seconds
assertEquals(Duration.ofSeconds(30), runtime.clock().currentTime());

// Chain multiple sleeps
runtime.unsafeRun(
    Effect.sleep(Duration.ofSeconds(5))
          .flatMap(__ -> Effect.sleep(Duration.ofSeconds(5)))
);
assertEquals(Duration.ofSeconds(40), runtime.clock().currentTime());
```

### ✅ `CapabilityHandler.forType()` — new recommended API

`CapabilityHandler.forType(Class<F>)` anchors the builder to a capability family, giving the compiler enough information to infer lambda parameter types without explicit casts. The old `builder()` is deprecated and will be removed in a future release.

```java
// Old (deprecated)
var handler = CapabilityHandler.builder()
    .on(StoreOps.Get.class,  (StoreOps.Get g) -> store.get(g.key()))
    .build();

// New (recommended)
var handler = CapabilityHandler.forType(StoreOps.class)
    .on(StoreOps.Get.class,  g -> store.get(g.key()))  // type inferred
    .build();
```

---

### ✅ `parTraverse` — parallel map over a collection

`Effects.parTraverse` collapses the two-step "build list then `parAll`" pattern into one declarative call. `parTraverseEither` is the tolerant variant — it collects both successes and failures instead of short-circuiting.

```java
// Before
List<Effect<Throwable, Result>> effs = new ArrayList<>();
for (Item item : items) {
    effs.add(checkInventory(item).<Throwable>toEffect());
}
Effect<Throwable, List<Result>> result = parAll(effs);

// After
Effect<Throwable, List<Result>> result =
    Effects.parTraverse(items, item -> checkInventory(item).<Throwable>toEffect());

// Collect all results — no short-circuit on failure
Effect<Throwable, List<Either<Throwable, Result>>> mixed =
    Effects.parTraverseEither(items, item -> checkInventory(item).<Throwable>toEffect());
```

### ✅ `Schedule<A, B>` — composable repeat-on-success scheduling

`RetryPolicy` handles the failure path (retry on error). `Schedule` handles the success path — repeat an effect on a cadence, while a predicate holds, or for a fixed number of iterations, and optionally accumulate the outputs. The two compose naturally.

```java
// Poll every 2 s, up to 10 times, until done — collect all status values
Schedule<Status, List<Status>> schedule = Schedule
    .<Status>fixed(Duration.ofSeconds(2))
    .recurs(10)
    .whileOutput(s -> !s.isDone())
    .collect();

Effect<Throwable, List<Status>> polling = schedule.repeat(checkStatus);

// Retry transient failures, then repeat on success
schedule.repeat(
    unstableCheck.retry(RetryPolicy.exponential(Duration.ofMillis(50)).maxAttempts(3))
);
```

**API surface:**
- Factories: `fixed(Duration)`, `exponential(Duration)`, `immediate()`
- Termination: `recurs(n)`, `whileOutput(pred)`, `untilOutput(pred)`, `maxDelay(Duration)`, `jittered(factor)`
- Accumulation: `collect()` — folds all outputs into `List<A>`
- Execution: `repeat(effect)` — stack-safe, integrates with the trampolined runtime

### ✅ `Effect.effect()` — no-handler generator entry point

When writing generator-style blocks that only use `ctx.yield()` and `ctx.call()` (no capability dispatch), the `CapabilityHandler` argument to `Effect.generate()` was dead ceremony. `Effect.effect()` removes it.

```java
// Before
Effect<IOException, String> pipeline = Effect.generate(ctx -> {
    String a = ctx.yield(fetchA());
    String b = ctx.yield(fetchB());
    return a + b;
}, CapabilityHandler.builder().build()); // dead noise

// After
Effect<IOException, String> pipeline = Effect.effect(ctx -> {
    String a = ctx.yield(fetchA());
    String b = ctx.yield(fetchB());
    return a + b;
});
```

---

## ⚠️ Breaking Changes

### `Effect.Sleep` is a new sealed subtype (source-breaking)

`Sleep<E>` is a new permitted type in the `public sealed interface Effect`. Any exhaustive `switch` expression or statement over all `Effect` variants will fail to compile without a `Sleep` branch.

**Before (compiles in 0.2.x, fails in 0.3.0):**
```java
return switch (effect) {
    case Effect.Pure<E,A>    p -> ...
    case Effect.Fail<E,A>    f -> ...
    case Effect.Suspend<E,A> s -> ...
    case Effect.Sleep<E>     s -> ...  // ← MISSING — compile error in 0.3.0
    // ... all other cases
};
```

**After (required in 0.3.0):**
```java
case Effect.Sleep<E> s -> performSleep(s.duration()); yield result;
```

Custom `EffectRuntime` implementations must add a `Sleep` branch and call their sleep implementation there.

### `CapabilityHandler.Builder` is now generic (source-breaking)

The nested class changed from `Builder` to `Builder<F extends Capability<?>>`. Explicit raw-type references produce compiler warnings.

**Before (raw type, 0.2.x):**
```java
CapabilityHandler.Builder builder = CapabilityHandler.builder();
```

**After (typed, 0.3.0):**
```java
CapabilityHandler.Builder<MyCapability> builder =
        CapabilityHandler.forType(MyCapability.class);
```

---

## 🔄 Migration Guide

Upgrade from 0.2.x to 0.3.0 with the following checklist:

- [ ] **Add `Sleep` branch to exhaustive `Effect` switches** — add `case Effect.Sleep<?> s -> ...` to any switch expression or statement that covers all `Effect` variants
- [ ] **Update custom `EffectRuntime` implementations** — intercept `Effect.Sleep` and call your sleep implementation (e.g. `Thread.sleep(sleep.duration())`)
- [ ] **Replace raw `CapabilityHandler.Builder` references** — use `CapabilityHandler.Builder<?>` or switch to `CapabilityHandler.forType(MyCapability.class)`
- [ ] **Replace `CapabilityHandler.builder()` calls** — use `CapabilityHandler.forType(MyCapability.class)` instead; behaviour is identical, type inference is better
- [ ] **No changes needed for `Effect<E,A>` code** — all existing `map`, `flatMap`, `catchAll`, `fork`, `retry`, `timeout`, and other combinators are unchanged

---

## ⚠️ Known Limitations

### `CapabilityHandler.Builder.build()` — flat sealed hierarchies only

The built handler resolves capabilities by checking the concrete class and then its direct interfaces. It correctly handles the standard pattern where sealed subtypes directly implement the registered interface. However, nested sealed hierarchies — where a concrete capability implements a supertype that is itself a subtype of the registered interface — are not resolved and will throw `UnsupportedOperationException` at runtime.

---

## 🧪 Testing

- **~93 new tests added** across Milestones 1 and 2 (effect laws, test utilities, typed effects)
- **Effect law tests** — 11 algebraic laws verified as runnable JUnit tests: functor identity/composition, monad left/right identity/associativity, error-channel laws, capability handler laws
- **`EffectAssertions`** — fluent assertions: `assertThat(effect).succeedsWith(42)`, `.failsWith(IOException.class)`
- **`TestRuntime` + `TestClock`** — deterministic sleep testing; all sleep-based tests run in milliseconds
- **Layer integration tests** — end-to-end coverage of horizontal composition, vertical composition, 3-capability nesting, `HandlerEnv.of()`, and pure effects

---

## 📦 Installation

### Maven

```xml
<dependency>
    <groupId>com.cajunsystems</groupId>
    <artifactId>roux</artifactId>
    <version>0.3.0</version>
</dependency>
```

### Gradle (Kotlin DSL)

```kotlin
implementation("com.cajunsystems:roux:0.3.0")
```

### Gradle (Groovy)

```groovy
implementation 'com.cajunsystems:roux:0.3.0'
```

**Requirements:** Java 21 or higher

---

## 🔗 Links

- **GitHub**: [github.com/CajunSystems/roux](https://github.com/CajunSystems/roux)
- **Maven Central**: [search.maven.org/artifact/com.cajunsystems/roux/0.3.0/jar](https://search.maven.org/artifact/com.cajunsystems/roux/0.3.0/jar)
- **Documentation**: [github.com/CajunSystems/roux/tree/main/docs](https://github.com/CajunSystems/roux/tree/main/docs)
- **Changelog**: [github.com/CajunSystems/roux/blob/main/CHANGELOG.md](https://github.com/CajunSystems/roux/blob/main/CHANGELOG.md)

---

**Full Changelog**: [v0.2.2...v0.3.0](https://github.com/CajunSystems/roux/compare/v0.2.2...v0.3.0)
