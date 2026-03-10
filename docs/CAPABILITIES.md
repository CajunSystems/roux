# Algebraic Effects via Capabilities

Roux provides a minimal, pragmatic capability system for algebraic effects. This allows you to write imperative-looking code that remains pure and testable.

## Core Concepts

### 1. Capability - Pure Effect Descriptions

A `Capability<R>` is a marker interface representing a request for an effect to be performed, returning type `R`. Capabilities are **pure data** - they describe what to do, not how.

```java
public interface Capability<R> {
    // Marker interface - capabilities are just data
}
```

#### Side-effect-only capabilities: use `Unit`, not `Void`

When a capability exists only for side effects (log, metric, audit, notify), model it as
`Capability<Unit>` and return `Unit.unit()` from handlers. This avoids `null` values and
keeps handlers explicit and consistent.

```java
import com.cajunsystems.roux.data.Unit;

sealed interface LogCapability<R> extends Capability<R> {
    record Info(String message) implements LogCapability<Unit> {}
}
```

### 2. CapabilityHandler - Effect Interpreters

A `CapabilityHandler<C>` interprets capabilities of type `C` and performs the actual side effects.

```java
@FunctionalInterface
public interface CapabilityHandler<C extends Capability<?>> {
    <R> R handle(C capability) throws Exception;
}
```

### 3. GeneratorContext - Imperative Effect Building

The `GeneratorContext` provides methods to perform capabilities within an effect:

```java
public interface GeneratorContext<E extends Throwable> {
    <R> R perform(Capability<R> capability) throws E;
    <R> Effect<E, R> lift(Capability<R> capability);  // NEW: Lift to effect
    <R> R call(ThrowingSupplier<R> operation) throws E;
    <E2 extends E, R> R yield(Effect<E2, R> effect) throws E;
    CapabilityHandler<Capability<?>> handler();
}
```

### 4. Effect.generate() - Create Effects from Generators

```java
Effect<Throwable, String> effect = Effect.generate(ctx -> {
    // Imperative-looking code
    String data = ctx.perform(new MyCapability.Fetch("url"));
    ctx.perform(new MyCapability.Log("Got: " + data));
    return data;
}, handler);
```

### 5. Capability-Effect Bridge

Convert capabilities directly to effects to leverage all Effect operators:

```java
// Convert capability to effect
Effect<Throwable, User> effect = new GetUser("123")
    .toEffect()
    .map(json -> parseJson(json, User.class))
    .retry(3)                          // retry up to 3 extra times on failure
    .timeout(Duration.ofSeconds(10));  // fail with TimeoutException after 10s

// Handler provided at runtime
User user = runtime.unsafeRunWithHandler(effect, handler);

// Or use Effect.from()
Effect<Throwable, String> getUser = Effect.from(new GetUser("123"));

// Parallel execution with zipPar
Effect<Throwable, Dashboard> dashboard = new GetUser("123")
    .toEffect()
    .zipPar(new GetOrders("123").toEffect(), Dashboard::new);
```

### 6. Building Handlers (0.2.0+)

Use the fluent `CapabilityHandler.builder()` for clean, lambda-friendly handler definitions:

```java
import com.cajunsystems.roux.data.Unit;

CapabilityHandler<Capability<?>> handler = CapabilityHandler.builder()
    .on(MyCapability.Fetch.class, fetch -> httpClient.get(fetch.url()))
    .on(MyCapability.Log.class,   log   -> {
        logger.info(log.message());
        return Unit.unit();
    })
    .build();
```

Compose multiple handlers without relying on exception-based dispatch:

```java
CapabilityHandler<Capability<?>> combined = CapabilityHandler.compose(
    httpHandler,
    dbHandler,
    logHandler
);
```

### 7. Typed Capability Handlers (0.2.2+)

For better type inference when building handlers, use `CapabilityHandler.forType(...)`:

```java
import com.cajunsystems.roux.capability.Capability;
import com.cajunsystems.roux.capability.CapabilityHandler;

sealed interface AppCapability<R> extends Capability<R> {
    record Log(String msg) implements AppCapability<Unit> {}
    record GetValue(String key) implements AppCapability<String> {}
}

var handler = CapabilityHandler.forType(AppCapability.class)
    .on(AppCapability.Log.class,      c -> { log.add(c.msg()); return Unit.unit(); })
    .on(AppCapability.GetValue.class, c -> "value-of-" + c.key())
    .build();

Effect<Throwable, String> effect = new AppCapability.GetValue("name").toEffect();
String value = runtime.unsafeRunWithHandler(effect, handler);
```

**Why use `forType(...)` instead of `builder()`?**

- **Perfect type inference** — lambda parameters (`c`) are correctly typed without hints
- **Zero overhead** — same performance as `builder()`, just better ergonomics
- **Cleaner code** — no need for explicit type annotations in lambdas

## Why This Design?

1. **Minimal & Unopinionated**: Roux provides only the infrastructure. You define your own capabilities.
2. **Type-Safe**: Sealed capability interfaces enable exhaustive pattern matching.
3. **Testable**: Swap handlers for testing - no mocking frameworks needed.
4. **Composable**: Handlers can be composed via `CapabilityHandler.compose()`.
5. **Separation of Concerns**: Effect description is separate from interpretation.

## What Roux Provides

- `Capability<R>` - Base marker interface with `toEffect()` method
- `CapabilityHandler<C>` - Handler interface with composition support
- `GeneratorContext<E>` - Context for performing and lifting capabilities
- `Effect.generate()` - Create effects from generator functions
- `Effect.from()` - Lift capabilities to effects
- `Effect.zipPar()` - Run effects in parallel
- `Effects.par()` - Static helpers for parallel execution
- Runtime integration with implicit handler context

## What You Provide

- Your domain-specific capability types (sealed interfaces)
- Handlers for your capabilities (production, test, etc.)
- Effect logic using `Effect.generate()`

## Example

See `examples/CustomCapabilities.md` for a complete example showing:
- How to define custom capabilities
- How to implement production and test handlers
- How to compose multiple handlers
- How to test effects without mocking

## Design Philosophy

Roux is an **effect runtime**, not a batteries-included framework. We provide the minimal primitives for algebraic effects, and you build domain-specific abstractions on top. This keeps Roux:

- **Focused**: Core effect runtime concerns only
- **Flexible**: No opinions about HTTP, databases, logging, etc.
- **Lightweight**: Minimal dependencies and surface area
- **Pragmatic**: Close to Java's natural idioms

For opinionated capability implementations, create a separate library that depends on Roux.
