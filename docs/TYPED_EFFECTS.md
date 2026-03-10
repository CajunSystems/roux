# Type-Safe Effects: HandlerEnv, EffectWithEnv, and Layers

Roux's layer system extends the capability API with compile-time verification that every capability an effect uses has a handler. No runtime `UnsupportedOperationException` from a forgotten handler — the program won't compile.

> **Prerequisites:** Read [CAPABILITIES.md](CAPABILITIES.md) first. This guide assumes familiarity with `Capability<R>`, `CapabilityHandler`, and `Effect<E,A>`.

---

## The Problem

With the basic capability API, handlers are supplied at the `unsafeRunWithHandler` call site. Nothing in the type system prevents you from forgetting a handler:

```java
// Works at compile time — fails at runtime if GetUser has no handler
runtime.unsafeRunWithHandler(effect, onlyDbHandler);  // UnsupportedOperationException!
```

The layer system encodes capability requirements in the type of the effect itself and verifies them at compile time.

---

## Phantom Types: `Empty` and `With<A, B>`

Two marker interfaces form a type-level set of capabilities. They are never instantiated — they exist purely to carry information through the type system.

| Phantom type | Meaning |
|---|---|
| `Empty` | No capabilities required |
| `With<A, B>` | Both `A` and `B` are present |

`With` nests right to represent more than two:

```java
// Two capabilities
HandlerEnv<With<DbOps, EmailOps>>

// Three capabilities (right-nested by convention)
HandlerEnv<With<DbOps, With<EmailOps, ConfigOps>>>
```

---

## `HandlerEnv<R>` — Typed Handler Environment

A `HandlerEnv<R>` wraps a `CapabilityHandler<Capability<?>>` at runtime but tracks, at compile time, which capabilities it covers. The phantom type parameter `R` is the "certificate" that lets `EffectWithEnv.run()` type-check.

### Creating a single-capability environment

```java
sealed interface StoreOps extends Capability<String> {
    record Get(String key)              implements StoreOps {}
    record Put(String key, String value) implements StoreOps {}
}

Map<String, String> store = new HashMap<>();

HandlerEnv<StoreOps> storeEnv = HandlerEnv.of(StoreOps.class, cap -> switch (cap) {
    case StoreOps.Get g -> store.getOrDefault(g.key(), "missing");
    case StoreOps.Put p -> { store.put(p.key(), p.value()); yield "ok"; }
});
```

`HandlerEnv.of` uses an F-bound — `C extends Capability<R>` — so the compiler captures `R` from the capability's own type parameter. A handler that returns the wrong type won't compile:

```java
// StoreOps extends Capability<String> — handler must return String
HandlerEnv<StoreOps> env = HandlerEnv.of(StoreOps.class, cap -> 42);  // compile error
```

### Combining environments with `and()`

```java
sealed interface LogOps extends Capability<Unit> {
    record Info(String message)  implements LogOps {}
    record Error(String message) implements LogOps {}
}

HandlerEnv<LogOps> logEnv = HandlerEnv.of(LogOps.class, cap -> switch (cap) {
    case LogOps.Info  m -> { sink.add(m.message()); yield Unit.unit(); }
    case LogOps.Error m -> { sink.add(m.message()); yield Unit.unit(); }
});

// Phantom type tracks both capabilities
HandlerEnv<With<StoreOps, LogOps>> fullEnv = storeEnv.and(logEnv);
```

### The empty environment

`HandlerEnv.empty()` is the base case for `Empty` requirements:

```java
HandlerEnv<Empty> nothing = HandlerEnv.empty();
```

### Wrapping a legacy handler

```java
// Caller asserts the phantom type — no runtime check
HandlerEnv<StoreOps> env = HandlerEnv.fromHandler(existingHandler);
```

### Extracting the underlying handler

```java
CapabilityHandler<Capability<?>> raw = env.toHandler();
runtime.unsafeRunWithHandler(effect, raw);
```

---

## `EffectWithEnv<R, E, A>` — Typed Effect

`EffectWithEnv<R, E, A>` is a thin wrapper around `Effect<E, A>` that statically declares which capability environment `R` the effect needs. `Effect<E, A>` itself is unchanged — `EffectWithEnv` is purely an annotation layer.

### Declaring an effect with capability requirements

```java
// Declare that this effect requires both StoreOps and LogOps
EffectWithEnv<With<StoreOps, LogOps>, Throwable, String> processUser =
    EffectWithEnv.of(
        new StoreOps.Put("user:1", "Alice")
            .toEffect()
            .flatMap(__ -> new LogOps.Info("Created Alice").toEffect())
            .flatMap(__ -> new StoreOps.Get("user:1").toEffect())
    );
```

`R` is a phantom parameter — the caller declares it explicitly. Java cannot infer phantom types from the wrapped effect.

### `map` and `flatMap` preserve `R`

```java
EffectWithEnv<StoreOps, Throwable, Integer> nameLength =
    EffectWithEnv.<StoreOps, Throwable, String>of(new StoreOps.Get("name").toEffect())
                 .map(String::length);
```

### Running a typed effect

`run()` only compiles when the environment's phantom type matches the effect's `R`:

```java
HandlerEnv<With<StoreOps, LogOps>> env = storeEnv.and(logEnv);

// Compiles — env covers With<StoreOps, LogOps>
String result = processUser.run(env, runtime);

// Does NOT compile — storeEnv is HandlerEnv<StoreOps>, not HandlerEnv<With<StoreOps, LogOps>>
// processUser.run(storeEnv, runtime);  // type error
```

### Effects with no capability requirements

```java
EffectWithEnv<Empty, RuntimeException, Integer> constant =
    EffectWithEnv.pure(Effect.succeed(42));

constant.run(HandlerEnv.empty(), runtime);  // compiles — Empty satisfies Empty
```

---

## `Layer<RIn, E, ROut>` — Dependency-Tracking Layer

A `Layer<RIn, E, ROut>` is a recipe: given a `HandlerEnv<RIn>`, build a `HandlerEnv<ROut>`, possibly performing effects during construction.

```
Layer<RIn, E, ROut>.build(HandlerEnv<RIn>) → Effect<E, HandlerEnv<ROut>>
```

This is the direct analog of `ZLayer[RIn, E, ROut]` from ZIO.

### Leaf layer — no dependencies

```java
Layer<Empty, RuntimeException, StoreOps> storeLayer =
    Layer.succeed(StoreOps.class, cap -> switch (cap) {
        case StoreOps.Get g -> store.getOrDefault(g.key(), "?");
        case StoreOps.Put p -> { store.put(p.key(), p.value()); yield "ok"; }
    });
```

`Layer.succeed` uses the same F-bound as `HandlerEnv.of`. Handler return type must match the capability's declared `R`.

### Layer with dependencies — `Layer.fromEffect`

When a handler needs to use other capabilities during construction (for example, reading SMTP config before building an email handler), use `fromEffect`:

```java
Layer<ConfigOps, Exception, EmailOps> emailLayer = Layer.fromEffect(
    EmailOps.class,
    configEnv -> Effect.suspend(() -> {
        // configEnv is available during layer construction
        String host = configEnv.toHandler().handle(new ConfigOps.Get("smtp.host"));
        return (EmailOps cap) -> switch (cap) {
            case EmailOps.Send s -> sendVia(host, s.to(), s.body());
        };
    })
);
```

### Building a layer

`build()` returns an `Effect` — run it to materialise the `HandlerEnv`:

```java
HandlerEnv<StoreOps> env =
    runtime.unsafeRun(storeLayer.build(HandlerEnv.empty()));
```

---

## Layer Composition

### Horizontal — `and()` (the `++` operator)

Both layers share the same input; their outputs are merged:

```java
//  storeLayer : Empty → StoreOps
//  logLayer   : Empty → LogOps
//  appLayer   : Empty → With<StoreOps, LogOps>
Layer<Empty, Throwable, With<StoreOps, LogOps>> appLayer = storeLayer.and(logLayer);

HandlerEnv<With<StoreOps, LogOps>> env =
    runtime.unsafeRun(appLayer.build(HandlerEnv.empty()));
```

Three capabilities via nesting:

```java
Layer<Empty, Throwable, With<With<StoreOps, LogOps>, ConfigOps>> triLayer =
    storeLayer.and(logLayer).and(configLayer);
```

### Vertical — `andProvide()` (the `>>>` operator)

Feed one layer's output into the next layer's input. Both outputs are retained:

```java
//  configLayer : Empty     → ConfigOps
//  emailLayer  : ConfigOps → EmailOps   (reads config during construction)
//  combined    : Empty     → With<ConfigOps, EmailOps>
Layer<Empty, Throwable, With<ConfigOps, EmailOps>> combined =
    configLayer.andProvide(emailLayer);
```

The type system threads the dependency: `emailLayer` requires `ConfigOps` and `configLayer` provides it. The resulting layer requires nothing and provides both.

### Error types

Composed layers always have error type `Throwable` — both individual error types are widened to their common supertype (Java has no union types for checked exceptions).

---

## Full Example

```java
// 1. Define capability families
sealed interface DbOps extends Capability<String> {
    record Query(String sql)  implements DbOps {}
    record Execute(String sql) implements DbOps {}
}

sealed interface AuditOps extends Capability<Unit> {
    record Log(String event) implements AuditOps {}
}

sealed interface ConfigOps extends Capability<String> {
    record Get(String key) implements ConfigOps {}
}

// 2. Build leaf layers
Layer<Empty, RuntimeException, DbOps> dbLayer = Layer.succeed(DbOps.class, cap -> switch (cap) {
    case DbOps.Query q  -> database.execute(q.sql());
    case DbOps.Execute e -> { database.execute(e.sql()); yield "ok"; }
});

Layer<Empty, RuntimeException, AuditOps> auditLayer = Layer.succeed(AuditOps.class, cap -> switch (cap) {
    case AuditOps.Log l -> { auditLog.write(l.event()); yield Unit.unit(); }
});

// 3. Compose horizontally — With<DbOps, AuditOps>
Layer<Empty, Throwable, With<DbOps, AuditOps>> appLayer = dbLayer.and(auditLayer);

// 4. Build the environment (runs IO during construction)
HandlerEnv<With<DbOps, AuditOps>> env =
    runtime.unsafeRun(appLayer.build(HandlerEnv.empty()));

// 5. Declare a typed effect
EffectWithEnv<With<DbOps, AuditOps>, Throwable, String> fetchUser =
    EffectWithEnv.of(
        new DbOps.Query("SELECT name FROM users WHERE id = 42")
            .toEffect()
            .flatMap(name ->
                new AuditOps.Log("fetched user: " + name)
                    .toEffect()
                    .map(__ -> name)
            )
    );

// 6. Run — compiler verifies env matches the effect's R
String name = fetchUser.run(env, runtime);
```

---

## API Reference

| Type | Package | Description |
|---|---|---|
| `Empty` | `capability` | Phantom: no capabilities |
| `With<A, B>` | `capability` | Phantom: A and B both present |
| `HandlerEnv<R>` | `capability` | Typed wrapper around `CapabilityHandler` |
| `EffectWithEnv<R,E,A>` | root | Typed wrapper around `Effect<E,A>` |
| `Layer<RIn,E,ROut>` | `capability` | Recipe for building `HandlerEnv` from another |

### `HandlerEnv<R>` methods

| Method | Returns | Description |
|---|---|---|
| `HandlerEnv.of(Class<C>, ThrowingFunction<C,R>)` | `HandlerEnv<C>` | Create from F-bounded handler (return type enforced) |
| `HandlerEnv.empty()` | `HandlerEnv<Empty>` | Base-case environment |
| `HandlerEnv.fromHandler(CapabilityHandler<Capability<?>>)` | `HandlerEnv<R>` | Wrap a legacy handler (caller asserts `R`) |
| `.and(HandlerEnv<S>)` | `HandlerEnv<With<R,S>>` | Merge two environments |
| `.toHandler()` | `CapabilityHandler<Capability<?>>` | Extract the underlying handler |

### `EffectWithEnv<R,E,A>` methods

| Method | Returns | Description |
|---|---|---|
| `EffectWithEnv.of(Effect<E,A>)` | `EffectWithEnv<R,E,A>` | Wrap an effect (caller declares `R`) |
| `EffectWithEnv.pure(Effect<E,A>)` | `EffectWithEnv<Empty,E,A>` | Wrap an effect with no requirements |
| `.map(Function<A,B>)` | `EffectWithEnv<R,E,B>` | Transform result, preserve `R` |
| `.flatMap(Function<A,EffectWithEnv<R,E,B>>)` | `EffectWithEnv<R,E,B>` | Chain, preserve `R` |
| `.run(HandlerEnv<R>, EffectRuntime)` | `A` | Run — only compiles when env covers `R` |
| `.effect()` | `Effect<E,A>` | Access the underlying effect |

### `Layer<RIn,E,ROut>` methods

| Method | Returns | Description |
|---|---|---|
| `Layer.succeed(Class<C>, ThrowingFunction<C,R>)` | `Layer<Empty,RuntimeException,C>` | Leaf layer, no dependencies |
| `Layer.fromEffect(Class<C>, Function<HandlerEnv<RIn>, Effect<E,ThrowingFunction<C,R>>>)` | `Layer<RIn,E,C>` | Layer that uses input env during construction |
| `.build(HandlerEnv<RIn>)` | `Effect<E,HandlerEnv<ROut>>` | Materialise the environment (SAM method) |
| `.and(Layer<RIn,E2,S>)` | `Layer<RIn,Throwable,With<ROut,S>>` | Horizontal composition |
| `.andProvide(Layer<ROut,E2,S>)` | `Layer<RIn,Throwable,With<ROut,S>>` | Vertical composition |

---

## Design Notes

### Where Java diverges from ZIO

| ZIO (Scala) | Roux (Java) |
|---|---|
| `ZIO[R, E, A]` — R built into the effect type | `EffectWithEnv<R, E, A>` — thin wrapper; `Effect<E, A>` unchanged |
| Intersection types `A with B with C` | Nested `With<A, With<B, C>>` phantom interfaces |
| Implicit `ZLayer` wiring | Explicit `.and()` / `.andProvide()` composition |
| Compiler synthesises proofs | Caller assembles `HandlerEnv` manually |

Roux's approach is more explicit at composition sites but requires no language extensions or annotation processing. All type safety comes from ordinary Java generics.

### Zero overhead

`EffectWithEnv` is a single-field wrapper. `HandlerEnv` holds one `CapabilityHandler` reference. Both are erased at runtime — the phantom type parameters exist only in `.class` file signatures. There is no performance difference compared to using `CapabilityHandler` directly.

### When capability families have mixed return types

The F-bound `C extends Capability<R>` at `HandlerEnv.of()` requires a single `R` across the capability family registered in one call. This encourages grouping capabilities with a consistent return type (a sealed interface where all variants share the same `Capability<R>` parent).

For a mixed-result group, use `CapabilityHandler.builder().on(...)` to register each subtype individually and wrap with `HandlerEnv.fromHandler()`.
