# Type-Safe Handlers and Layer-Based Dependency Tracking

Roux extends the capability system with a ZIO-style layer mechanism that lets the compiler verify at build time that every capability an effect uses has a handler.  No runtime `UnsupportedOperationException` because a handler was forgotten; the program simply won't compile.

## Motivation

With the basic `CapabilityHandler` API you supply a handler at the call site of `unsafeRunWithHandler`.  Nothing in the type system prevents you from forgetting a handler or passing the wrong one.

The layer system solves this by encoding capability requirements as phantom type parameters:

```
Effect<E, A>                 — untyped, no capability info
TypedEffect<R, E, A>         — R encodes which capabilities are required
HandlerEnv<R>                — R encodes which capabilities are provided
Layer<RIn, E, ROut>          — recipe: given RIn, build ROut
```

`TypedEffect.run(env, runtime)` only compiles when `env` is a `HandlerEnv<R>` whose phantom type matches the effect's `R` exactly.

---

## Phantom Types: `Empty` and `With<A, B>`

Two interfaces form a type-level set of capabilities:

| Phantom type | Meaning |
|---|---|
| `Empty` | No capabilities — the base case |
| `With<A, B>` | Both `A` and `B` capabilities are present |

`With` nests to represent arbitrary numbers:

```java
// Two capabilities
HandlerEnv<With<DbOps, EmailOps>>

// Three capabilities (right-nested by convention)
HandlerEnv<With<DbOps, With<EmailOps, ConfigOps>>>
```

No instance of `Empty` or `With` is ever created; they exist purely to carry type information at compile time.

---

## `HandlerEnv<R>` — Typed Handler Environment

A `HandlerEnv<R>` wraps a `CapabilityHandler<Capability<?>>` at runtime but tracks, at compile time, which capabilities it covers.

### Creating a single-capability environment

```java
sealed interface StoreOps extends Capability<String> {
    record Get(String key) implements StoreOps {}
    record Put(String key, String value) implements StoreOps {}
}

HandlerEnv<StoreOps> storeEnv = HandlerEnv.of(StoreOps.class, cap -> switch (cap) {
    case StoreOps.Get g -> store.getOrDefault(g.key(), "missing");
    case StoreOps.Put p -> { store.put(p.key(), p.value()); yield "ok"; }
});
```

### F-bounded return type

`HandlerEnv.of` — and all other factory methods — use an F-bound:

```java
static <R, C extends Capability<R>> HandlerEnv<C> of(
        Class<C> type,
        ThrowingFunction<C, R> handler)
```

`C extends Capability<R>` captures `R` from the capability's own type parameter.  The compiler now knows the handler must return `R` — the exact return type the capability declares — with no wildcards.

### Combining environments with `and()`

```java
HandlerEnv<LogOps> logEnv = HandlerEnv.of(LogOps.class, cap -> switch (cap) {
    case LogOps.Info  m -> { logSink.add(m.message()); yield Unit.unit(); }
    case LogOps.Error m -> { logSink.add(m.message()); yield Unit.unit(); }
});

// Phantom type tracks both — With<StoreOps, LogOps>
HandlerEnv<With<StoreOps, LogOps>> full = storeEnv.and(logEnv);
```

### Interop with existing handlers

```java
// Wrap any pre-built CapabilityHandler; caller asserts the phantom type
HandlerEnv<StoreOps> env = HandlerEnv.fromHandler(legacyHandler);
```

---

## `TypedEffect<R, E, A>` — Effect with Declared Requirements

`TypedEffect<R, E, A>` is a thin wrapper around `Effect<E, A>` that statically declares which capability environment `R` the effect needs.

```java
// Declare that this effect requires both StoreOps and LogOps
TypedEffect<With<StoreOps, LogOps>, Throwable, String> processUser = TypedEffect.of(
    new StoreOps.Put("user:1", "Alice")
        .toEffect()
        .flatMap(__ -> new LogOps.Info("Created Alice").toEffect())
        .flatMap(__ -> new StoreOps.Get("user:1").toEffect())
);
```

`map` and `flatMap` preserve the requirement type `R`:

```java
TypedEffect<StoreOps, Throwable, Integer> nameLength =
    TypedEffect.<StoreOps, Throwable, String>of(new StoreOps.Get("name").toEffect())
               .map(String::length);
```

### Running a typed effect

```java
HandlerEnv<With<StoreOps, LogOps>> env = storeEnv.and(logEnv);

// Compiles — env covers With<StoreOps, LogOps>
String result = processUser.run(env, runtime);

// Does NOT compile — storeEnv alone is HandlerEnv<StoreOps>, not HandlerEnv<With<StoreOps, LogOps>>
// processUser.run(storeEnv, runtime);  // ← type error
```

For effects with no capability requirements, use `TypedEffect.pure`:

```java
TypedEffect<Empty, RuntimeException, Integer> constant = TypedEffect.pure(Effect.succeed(42));
constant.run(HandlerEnv.empty(), runtime);  // compiles — empty satisfies Empty
```

---

## `Layer<RIn, E, ROut>` — Dependency-Tracking Layer

A `Layer<RIn, E, ROut>` is a recipe: given a `HandlerEnv<RIn>`, build a `HandlerEnv<ROut>`, possibly performing effects during construction.

This is the direct analog of `ZLayer[RIn, E, ROut]` from ZIO.

```
Layer<RIn, E, ROut>.build(HandlerEnv<RIn>) → Effect<E, HandlerEnv<ROut>>
```

### Leaf layer — no dependencies

```java
Layer<Empty, RuntimeException, StoreOps> storeLayer =
    Layer.succeed(StoreOps.class, cap -> switch (cap) {
        case StoreOps.Get g -> store.getOrDefault(g.key(), "?");
        case StoreOps.Put p -> { store.put(p.key(), p.value()); yield "ok"; }
    });
```

`Layer.succeed` uses the same F-bound as `HandlerEnv.of`: `<R, C extends Capability<R>>`.  The handler lambda must return `R`.

### Layer with dependencies — `Layer.fromEffect`

When a handler needs to perform other capabilities during construction (e.g. read SMTP settings from config), use `fromEffect`:

```java
Layer<ConfigOps, Exception, EmailOps> emailLayer = Layer.fromEffect(
    EmailOps.class,
    configEnv -> Effect.suspend(() -> {
        // configEnv is a HandlerEnv<ConfigOps> available during construction
        String host = configEnv.toHandler().handle(new ConfigOps.GetConfig("smtp.host"));
        return (EmailOps cap) -> switch (cap) {
            case EmailOps.Send s -> sendViaSmtp(host, s.to(), s.body());
        };
    })
);
```

### Horizontal composition — `and` (the `++` operator)

Both layers share the same input and their outputs are merged:

```java
// ++ : same input (Empty), merged output With<StoreOps, LogOps>
Layer<Empty, Throwable, With<StoreOps, LogOps>> appLayer = storeLayer.and(logLayer);

HandlerEnv<With<StoreOps, LogOps>> env =
    runtime.unsafeRun(appLayer.build(HandlerEnv.empty()));
```

### Vertical composition — `andProvide` (the `>>>` operator)

Feed one layer's output directly into the next layer's input.  Both outputs are retained:

```java
// configLayer : Empty     → ConfigOps
// emailLayer  : ConfigOps → EmailOps  (reads config during construction)
// combined    : Empty     → With<ConfigOps, EmailOps>
Layer<Empty, Throwable, With<ConfigOps, EmailOps>> combined =
    configLayer.andProvide(emailLayer);
```

The type system threads the dependency chain: `emailLayer` requires `ConfigOps`, and `configLayer` provides it.  The resulting layer asks for nothing and provides both.

---

## Full Example: Layers → Environment → Typed Effect

```java
// 1. Define capability families
sealed interface DbOps extends Capability<String> {
    record Query(String sql) implements DbOps {}
}

sealed interface AuditOps extends Capability<Unit> {
    record Log(String event) implements AuditOps {}
}

// 2. Build layers
Layer<Empty, RuntimeException, DbOps> dbLayer =
    Layer.succeed(DbOps.class, cap -> switch (cap) {
        case DbOps.Query q -> database.execute(q.sql());
    });

Layer<Empty, RuntimeException, AuditOps> auditLayer =
    Layer.succeed(AuditOps.class, cap -> switch (cap) {
        case AuditOps.Log l -> { auditLog.write(l.event()); yield Unit.unit(); }
    });

// 3. Compose horizontally — With<DbOps, AuditOps>
Layer<Empty, Throwable, With<DbOps, AuditOps>> appLayer = dbLayer.and(auditLayer);

// 4. Build the environment (effectful, runs IO during layer construction)
HandlerEnv<With<DbOps, AuditOps>> env =
    runtime.unsafeRun(appLayer.build(HandlerEnv.empty()));

// 5. Declare a typed effect
TypedEffect<With<DbOps, AuditOps>, Throwable, String> fetchUser = TypedEffect.of(
    new DbOps.Query("SELECT name FROM users WHERE id = 42")
        .toEffect()
        .tap(name -> new AuditOps.Log("fetched user: " + name).toEffect())
);

// 6. Run — compiler verifies env matches the effect's requirement
String name = fetchUser.run(env, runtime);
```

---

## F-Bounded Polymorphism in the Builder API

Every factory method in the layer system uses an F-bound to eliminate wildcards:

| Before | After |
|---|---|
| `<C extends Capability<?>>` | `<R, C extends Capability<R>>` |
| `ThrowingFunction<C, ?>` | `ThrowingFunction<C, R>` |

This applies to:

- `CapabilityHandler.Builder.on(Class<C>, ThrowingFunction<C, R>)`
- `HandlerEnv.of(Class<C>, ThrowingFunction<C, R>)`
- `Layer.succeed(Class<C>, ThrowingFunction<C, R>)`
- `Layer.fromEffect(Class<C>, Function<HandlerEnv<RIn>, Effect<E, ThrowingFunction<C, R>>>)`

The compiler now rejects handlers that return the wrong type:

```java
// StoreOps extends Capability<String> — handler must return String
HandlerEnv<StoreOps> env = HandlerEnv.of(StoreOps.class, cap ->
    42  // ← compile error: int is not String
);
```

---

## Design Notes

### Where Java diverges from ZIO

| ZIO (Scala) | Roux (Java) |
|---|---|
| `ZIO[R, E, A]` — R built into the effect type | `TypedEffect<R, E, A>` — thin wrapper; `Effect<E, A>` unchanged |
| Intersection types `A with B with C` | Nested `With<A, With<B, C>>` phantom interfaces |
| Implicit `ZLayer` wiring | Explicit `.and()` / `.andProvide()` composition |
| Compiler synthesises proofs | Caller assembles `HandlerEnv` manually |

Roux's approach is more verbose at composition sites but requires no language extensions or annotation processing.  All type safety comes from ordinary Java generics.

### When capability families have mixed return types

The F-bound `C extends Capability<R>` requires a **single** `R` across the entire capability family registered in one `HandlerEnv.of` call.  This encourages grouping capabilities with a consistent return type (a sealed interface where all variants share the same `Capability<R>` parent).

If you have a mixed-result capability group, register each concrete subtype individually using `CapabilityHandler.builder().on(...)` and wrap with `HandlerEnv.fromHandler`.

### Zero overhead

`TypedEffect` is a single-field wrapper; `HandlerEnv` is a functional interface holding an existing `CapabilityHandler`.  Both are erased at runtime.  The phantom type parameters exist only in `.class` file signatures.
