# Effect API Reference

Complete reference for Roux's Effect system.

## Table of Contents

1. [Effect Constructors](#effect-constructors)
2. [Transformation Operators](#transformation-operators)
3. [Error Handling](#error-handling)
4. [Concurrency](#concurrency)
5. [Capabilities](#capabilities)
6. [Runtime Execution](#runtime-execution)
7. [Helper Types](#helper-types)

---

## Effect Constructors

### `Effect.succeed(value)`

Create an effect that succeeds with a value.

```java
Effect<Throwable, String> effect = Effect.succeed("hello");
// Result: "hello"
```

**Type:** `<E extends Throwable, A> Effect<E, A>`

---

### `Effect.fail(error)`

Create an effect that fails with an error.

```java
Effect<IOException, String> effect = Effect.fail(new IOException("error"));
// Throws: IOException("error")
```

**Type:** `<E extends Throwable, A> Effect<E, A>`

---

### `Effect.suspend(thunk)`

Create an effect from a lazy computation. The computation is executed when the effect runs.

```java
Effect<Throwable, String> effect = Effect.suspend(() -> {
    System.out.println("Computing...");
    return "result";
});
// Prints "Computing..." only when effect runs
```

**Type:** `<E extends Throwable, A> Effect<E, A>`

**Use Cases:**
- Lazy evaluation
- Wrapping side effects
- Deferring expensive computations

---

### `Effect.generate(generator, handler)`

Create an effect using generator-style imperative code with capabilities.

```java
Effect<Throwable, String> effect = Effect.generate(ctx -> {
    String data = ctx.perform(new FetchData("url"));
    ctx.perform(new LogInfo("Got: " + data));
    return data.toUpperCase();
}, handler);
```

**Type:** `<E extends Throwable, A> Effect<E, A>`

**Generator Context Methods:**
- `ctx.perform(capability)` - Execute a capability
- `ctx.lift(capability)` - Convert capability to effect without executing
- `ctx.call(operation)` - Execute a direct operation
- `ctx.yield(effect)` - Execute another effect

---

### `Effect.from(capability)`

Lift a capability into an effect. Handler is provided at runtime.

```java
Effect<Throwable, User> effect = Effect.from(new GetUser("123"));
// Handler provided via runtime.unsafeRunWithHandler(effect, handler)
```

**Type:** `<E extends Throwable, R> Effect<E, R>`

---

### `Effect.scoped(body)`

Create a scoped effect for **structured concurrency**. All forked effects within the scope are automatically managed and cancelled when the scope exits.

```java
Effect<Throwable, String> effect = Effect.scoped(scope -> {
    // Fork tasks within the scope
    Effect<Throwable, Fiber<Throwable, String>> fiber1 = task1.forkIn(scope);
    Effect<Throwable, Fiber<Throwable, String>> fiber2 = task2.forkIn(scope);
    
    // Wait for results
    return fiber1.flatMap(f1 ->
        fiber2.flatMap(f2 ->
            f1.join().flatMap(r1 ->
                f2.join().map(r2 -> r1 + r2)
            )
        )
    );
    // Both tasks automatically cancelled if scope exits early
});
```

**Type:** `<E extends Throwable, A> Effect<E, A>`

**Scope Methods:**
- `scope.fork(effect)` - Fork an effect within the scope
- `scope.cancelAll()` - Manually cancel all forked effects
- `scope.isCancelled()` - Check if scope is cancelled

**Guarantees:**
- ✅ All forked effects are tracked
- ✅ Automatic cancellation on scope exit (success, error, or early return)
- ✅ No leaked threads or resources
- ✅ Built on Java's `StructuredTaskScope` (JEP 453)

**See:** [Structured Concurrency Guide](STRUCTURED_CONCURRENCY.md) for comprehensive documentation and patterns.

---

## Transformation Operators

### `map(f)`

Transform the success value of an effect.

```java
Effect<Throwable, String> effect = Effect.succeed(42)
    .map(n -> "Number: " + n);
// Result: "Number: 42"
```

**Type:** `<B> Effect<E, B>`

**Example - Multiple transformations:**
```java
Effect<Throwable, Integer> result = Effect.succeed("123")
    .map(Integer::parseInt)
    .map(n -> n * 2)
    .map(n -> n + 10);
// Result: 256
```

---

### `flatMap(f)`

Chain effects sequentially. The function receives the success value and returns a new effect.

```java
Effect<Throwable, User> effect = Effect.succeed("123")
    .flatMap(id -> fetchUser(id))
    .flatMap(user -> enrichUser(user));
```

**Type:** `<B> Effect<E, B>`

**Example - Sequential workflow:**
```java
Effect<Throwable, Order> placeOrder = Effect.succeed(userId)
    .flatMap(id -> getUser(id))
    .flatMap(user -> validateUser(user))
    .flatMap(user -> createOrder(user))
    .flatMap(order -> chargePayment(order))
    .flatMap(order -> sendConfirmation(order));
```

---

## Error Handling

### `catchAll(handler)`

Recover from all errors by providing a fallback effect.

```java
Effect<Throwable, String> effect = fetchData()
    .catchAll(error -> {
        log.error("Failed: " + error);
        return Effect.succeed("default");
    });
```

**Type:** `Effect<E, A>`

**Example - Retry with fallback:**
```java
Effect<Throwable, String> resilient = fetchFromPrimary()
    .catchAll(e1 -> fetchFromSecondary())
    .catchAll(e2 -> Effect.succeed("cached-value"));
```

---

### `orElse(fallback)`

Provide a fallback effect if this effect fails.

```java
Effect<Throwable, String> effect = fetchFromCache()
    .orElse(fetchFromDatabase())
    .orElse(Effect.succeed("default"));
```

**Type:** `Effect<E, A>`

---

### `mapError(f)`

Transform the error type of an effect.

```java
Effect<AppError, String> effect = Effect.<IOException, String>fail(new IOException("IO error"))
    .mapError(ioError -> new AppError("Network failed", ioError));
```

**Type:** `<E2 extends Throwable> Effect<E2, A>`

**Example - Error normalization:**
```java
Effect<DomainError, User> normalized = fetchUser(id)
    .mapError(e -> switch(e) {
        case IOException io -> new DomainError.NetworkError(io);
        case SQLException sql -> new DomainError.DatabaseError(sql);
        default -> new DomainError.UnknownError(e);
    });
```

---

### `widen()`

Widen the error type to `Throwable`. This is a safe operation useful for composing effects with different error types.

```java
Effect<IOException, String> io = fetchFile();
Effect<SQLException, User> sql = queryUser();

// Widen both to Throwable for composition
Effect<Throwable, String> combined = io.widen()
    .flatMap(data -> sql.widen().map(user -> process(data, user)));
```

**Type:** `Effect<Throwable, A>`

**Use case:** Composing effects with different specific error types.

---

### `narrow()`

Narrow the error type to a more specific exception type. 

⚠️ **Warning:** This is an unsafe cast. Use only when you have external knowledge that the error type is more specific than declared. If the actual error at runtime is not of the narrowed type, you will get a `ClassCastException`.

```java
// Library returns generic Throwable
Effect<Throwable, Config> generic = loadFromLibrary();

// You know it only throws ConfigException
Effect<ConfigException, Config> specific = generic.narrow();

// Now you can handle ConfigException specifically
Effect<ConfigException, Config> handled = specific.catchAll(e -> 
    Effect.succeed(defaultConfig())
);
```

**Type:** `<E2 extends E> Effect<E2, A>`

**Use case:** Type refinement when you know the actual error type is more specific.

---

### `attempt()`

Convert an effect into one that cannot fail, wrapping the result in `Either<E, A>`.

```java
Effect<Throwable, Either<IOException, String>> safe = fetchData().attempt();

Either<IOException, String> result = runtime.unsafeRun(safe);
switch (result) {
    case Either.Left<IOException, String> left -> 
        System.err.println("Error: " + left.value());
    case Either.Right<IOException, String> right -> 
        System.out.println("Success: " + right.value());
}
```

**Type:** `Effect<Throwable, Either<E, A>>`

---

## Concurrency

### `fork()`

Run an effect on a separate virtual thread, returning a `Fiber` handle.

```java
Effect<Throwable, Fiber<Throwable, String>> fiberEffect = longRunningTask.fork();

Fiber<Throwable, String> fiber = runtime.unsafeRun(fiberEffect);
String result = runtime.unsafeRun(fiber.join());
```

**Type:** `Effect<Throwable, Fiber<E, A>>`

**Fiber Methods:**
- `join()` - Wait for fiber to complete and get result
- `interrupt()` - Cancel the fiber
- `id()` - Get unique fiber ID

---

### `forkIn(scope)`

Fork an effect within a specific scope for structured concurrency.

```java
Effect<Throwable, String> effect = Effect.scoped(scope -> {
    Fiber<Throwable, String> fiber1 = scope.fork(task1);
    Fiber<Throwable, String> fiber2 = scope.fork(task2);
    
    String r1 = fiber1.join();
    String r2 = fiber2.join();
    
    return r1 + r2;
    // Both fibers auto-cancelled if scope exits early
});
```

**Type:** `Effect<Throwable, Fiber<E, A>>`

---

### `zipPar(other, combiner)`

Run two effects in parallel and combine their results.

```java
Effect<Throwable, Dashboard> dashboard = fetchUser("123")
    .zipPar(fetchOrders("123"), (user, orders) -> 
        new Dashboard(user, orders)
    );
```

**Type:** `<B, C> Effect<Throwable, C>`

**Example - Parallel data fetching:**
```java
record Summary(User user, List<Order> orders, Preferences prefs) {}

Effect<Throwable, Summary> summary = fetchUser(id)
    .zipPar(fetchOrders(id), Tuple2::new)
    .zipPar(fetchPreferences(id), (userOrders, prefs) ->
        new Summary(userOrders._1(), userOrders._2(), prefs)
    );
```

---

### `Effects.par(ea, eb, f)` - Static Helpers

Run 2, 3, or 4 effects in parallel with a combiner function.

```java
import static com.cajunsystems.roux.Effects.*;

// 2 effects
Effect<Throwable, Result> result = par(
    fetchUser(id),
    fetchOrders(id),
    Result::new
);

// 3 effects
Effect<Throwable, Dashboard> dashboard = par(
    fetchUser(id),
    fetchOrders(id),
    fetchPreferences(id),
    Dashboard::new
);

// 4 effects
Effect<Throwable, Summary> summary = par(
    fetchUser(id),
    fetchOrders(id),
    fetchPreferences(id),
    fetchNotifications(id),
    Summary::new
);
```

**Types:**
- `par(ea, eb, f)` - 2 effects
- `par(ea, eb, ec, f)` - 3 effects
- `par(ea, eb, ec, ed, f)` - 4 effects

---

## Capabilities

### `Capability.toEffect()`

Convert a capability to an effect. Handler is implicit from execution context.

```java
sealed interface MyCapability<R> extends Capability<R> {
    record GetUser(String id) implements MyCapability<User> {}
    record GetOrders(String userId) implements MyCapability<List<Order>> {}
}

// Convert to effect
Effect<Throwable, User> userEffect = new MyCapability.GetUser("123")
    .toEffect()
    .map(user -> enrichUser(user))
    .catchAll(e -> Effect.succeed(User.GUEST));

// Run with handler
User user = runtime.unsafeRunWithHandler(userEffect, handler);
```

**Type:** `<E extends Throwable> Effect<E, R>`

**Benefits:**
- All Effect operators work (map, flatMap, retry, timeout, zipPar, etc.)
- Handler provided at runtime
- Clean, composable API

---

### `CapabilityHandler` - Define Handlers

Implement capability interpreters.

```java
class ProductionHandler implements CapabilityHandler<MyCapability<?>> {
    @Override
    @SuppressWarnings("unchecked")
    public <R> R handle(MyCapability<?> capability) throws Exception {
        return switch (capability) {
            case MyCapability.GetUser get -> 
                (R) httpClient.get("/users/" + get.id());
            case MyCapability.GetOrders getOrders -> 
                (R) database.query("SELECT * FROM orders WHERE user_id = ?", 
                    getOrders.userId());
        };
    }
}
```

**Handler Composition:**

```java
// Combine multiple handlers
CapabilityHandler<Capability<?>> combined = CapabilityHandler.compose(
    httpHandler,
    dbHandler,
    logHandler
);

// Fallback chain
CapabilityHandler<Capability<?>> withFallback = primaryHandler
    .orElse(secondaryHandler)
    .orElse(defaultHandler);
```

---

### `GeneratorContext` - Imperative Style

Use capabilities in generator-style code.

```java
Effect<Throwable, Result> workflow = Effect.generate(ctx -> {
    // Perform capability directly
    User user = ctx.perform(new GetUser("123"));
    
    // Lift capability to effect for composition
    Effect<Throwable, List<Order>> ordersEffect = ctx.lift(new GetOrders(user.id()))
        .map(orders -> orders.stream()
            .filter(Order::isActive)
            .toList());
    
    // Yield to execute the effect
    List<Order> orders = ctx.yield(ordersEffect);
    
    // Call direct operations
    String formatted = ctx.call(() -> formatResult(user, orders));
    
    return new Result(user, orders, formatted);
}, handler);
```

**Context Methods:**
- `perform(capability)` - Execute capability immediately
- `lift(capability)` - Convert to effect without executing
- `call(operation)` - Execute direct operation
- `yield(effect)` - Execute another effect
- `handler()` - Get current handler

---

## Runtime Execution

### `EffectRuntime.unsafeRun(effect)`

Execute an effect synchronously, throwing errors.

```java
EffectRuntime runtime = DefaultEffectRuntime.create();

String result = runtime.unsafeRun(fetchData());
// Blocks until complete, throws on error
```

**Type:** `<E extends Throwable, A> A throws E`

---

### `EffectRuntime.unsafeRunWithHandler(effect, handler)`

Execute an effect with a capability handler.

```java
EffectRuntime runtime = DefaultEffectRuntime.create();
CapabilityHandler<Capability<?>> handler = new ProductionHandler();

Effect<Throwable, User> effect = new GetUser("123")
    .toEffect()
    .map(user -> enrichUser(user));

User user = runtime.unsafeRunWithHandler(effect, handler);
```

**Type:** `<E extends Throwable, A> A throws E`

---

### `EffectRuntime.runAsync(effect, onSuccess, onError)`

Execute an effect asynchronously with callbacks.

```java
CancellationHandle handle = runtime.runAsync(
    longRunningTask,
    result -> System.out.println("Success: " + result),
    error -> System.err.println("Error: " + error)
);

// Cancel if needed
handle.cancel();

// Wait for completion
handle.await();
```

**Type:** `<E extends Throwable, A> CancellationHandle`

**CancellationHandle Methods:**
- `cancel()` - Cancel the running effect
- `isCancelled()` - Check if cancelled
- `await()` - Block until complete
- `await(timeout)` - Block with timeout

---

## Helper Types

### `Either<L, R>`

Represents a value that can be either Left (error) or Right (success).

```java
Effect<Throwable, Either<IOException, String>> safe = fetchData().attempt();

Either<IOException, String> result = runtime.unsafeRun(safe);

// Pattern matching
String value = switch (result) {
    case Either.Left<IOException, String> left -> "Error: " + left.value();
    case Either.Right<IOException, String> right -> right.value();
};
```

---

### `Tuple2<A, B>` and `Tuple3<A, B, C>`

Simple tuple types for combining values.

```java
import com.cajunsystems.roux.Effects.Tuple2;
import com.cajunsystems.roux.Effects.Tuple3;

Tuple2<String, Integer> pair = new Tuple2<>("hello", 42);
String first = pair._1();
Integer second = pair._2();

Tuple3<String, Integer, Boolean> triple = new Tuple3<>("hello", 42, true);
```

---

### `Function3<A, B, C, R>` and `Function4<A, B, C, D, R>`

Function types for 3 and 4 arguments.

```java
import com.cajunsystems.roux.Effects.Function3;
import com.cajunsystems.roux.Effects.Function4;

Function3<String, Integer, Boolean, Result> f3 = (a, b, c) -> 
    new Result(a, b, c);

Function4<String, Integer, Boolean, Double, Result> f4 = (a, b, c, d) -> 
    new Result(a, b, c, d);
```

---

## Common Patterns

### Sequential Workflow

```java
Effect<Throwable, Order> placeOrder = Effect.succeed(userId)
    .flatMap(id -> validateUser(id))
    .flatMap(user -> createOrder(user))
    .flatMap(order -> processPayment(order))
    .flatMap(order -> sendConfirmation(order))
    .catchAll(error -> {
        logError(error);
        return Effect.fail(new OrderError(error));
    });
```

### Parallel Workflow

```java
import static com.cajunsystems.roux.Effects.*;

Effect<Throwable, Dashboard> dashboard = par(
    fetchUser(userId),
    fetchOrders(userId),
    fetchAnalytics(userId),
    Dashboard::new
);
```

### Retry Pattern

```java
Effect<Throwable, String> withRetry = fetchData()
    .catchAll(e1 -> {
        Thread.sleep(1000);
        return fetchData();
    })
    .catchAll(e2 -> {
        Thread.sleep(2000);
        return fetchData();
    })
    .catchAll(e3 -> Effect.succeed("default"));
```

### Resource Management

```java
Effect<Throwable, String> readFile = Effect.scoped(scope -> {
    FileHandle file = openFile("data.txt");
    scope.fork(Effect.suspend(() -> {
        file.close();
        return null;
    }));
    
    return readContent(file);
    // file.close() called automatically when scope exits
});
```

### Capability-Based Architecture

```java
// Define capabilities
sealed interface AppCapability<R> extends Capability<R> {
    record FetchUser(String id) implements AppCapability<User> {}
    record SaveOrder(Order order) implements AppCapability<Void> {}
    record SendEmail(String to, String body) implements AppCapability<Void> {}
}

// Business logic (pure, testable)
Effect<Throwable, Result> workflow = new AppCapability.FetchUser("123")
    .toEffect()
    .flatMap(user -> new AppCapability.SaveOrder(createOrder(user)).toEffect())
    .flatMap(v -> new AppCapability.SendEmail(user.email(), "Order created").toEffect())
    .map(v -> new Result("success"));

// Production
runtime.unsafeRunWithHandler(workflow, productionHandler);

// Testing
runtime.unsafeRunWithHandler(workflow, testHandler);
```

---

## Type Safety

Roux's Effect system is fully type-safe:

```java
// Error type is tracked
Effect<IOException, String> io = fetchFile();
Effect<SQLException, User> sql = queryUser();

// Composition maintains type safety - use widen() for convenience
Effect<Throwable, String> combined = io
    .widen()  // Widen error type from IOException to Throwable
    .flatMap(data -> sql.widen());

// Or use mapError for more control
Effect<Throwable, String> combined2 = io
    .mapError(e -> (Throwable) e)
    .flatMap(data -> sql.mapError(e -> (Throwable) e));

// Narrow error types when you know the specific type (unsafe cast)
Effect<Throwable, Config> generic = loadFromLibrary();
Effect<ConfigException, Config> specific = generic.narrow();

// Compiler catches type errors
// io.flatMap(data -> sql);  // ❌ Won't compile - error types don't match
```

---

## Performance Characteristics

- **Virtual Threads**: All effects run on virtual threads (Project Loom)
- **Lazy Evaluation**: Effects are descriptions, not executions
- **Zero Overhead**: Effect combinators are just data structures until executed
- **Structured Concurrency**: Automatic cleanup of forked effects
- **Cancellation**: Built-in support for interrupting running effects

---

## Best Practices

1. **Use `succeed` and `suspend` for pure values vs side effects**
   ```java
   Effect.succeed(42)           // Pure value
   Effect.suspend(() -> readFile())  // Side effect
   ```

2. **Prefer `flatMap` for sequential, `zipPar` for parallel**
   ```java
   a.flatMap(x -> b.map(y -> combine(x, y)))  // Sequential
   a.zipPar(b, (x, y) -> combine(x, y))       // Parallel
   ```

3. **Use capabilities for testability**
   ```java
   // Instead of direct calls
   Effect.suspend(() -> httpClient.get(url))
   
   // Use capabilities
   new HttpGet(url).toEffect()
   ```

4. **Handle errors explicitly**
   ```java
   effect
       .catchAll(e -> fallback)
       .orElse(defaultEffect)
   ```

5. **Use scoped for resource management**
   ```java
   Effect.scoped(scope -> {
       // Resources auto-cleaned when scope exits
   })
   ```

---

## See Also

- [Capabilities Guide](CAPABILITIES.md) - Algebraic effects system
- [Capability Recipes](CAPABILITY_RECIPES.md) - Common patterns
- [Custom Capabilities Example](../examples/CustomCapabilities.md) - Complete example
