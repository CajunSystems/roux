# Roux

<div align="center">
  <img src="docs/img/roux-logo.png" alt="Roux Logo" width="200" />

**A lightweight, pragmatic effect system for modern Java**

[![Maven Central](https://img.shields.io/maven-central/v/com.cajunsystems/roux.svg)](https://search.maven.org/artifact/com.cajunsystems/roux)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
</div>

---

Roux is a foundational effect system for the JVM that embraces Java's native capabilities. Built on virtual threads and structured concurrency, Roux provides a clean, composable way to handle side effects while staying close to Java's natural behavior.

## Why Roux?

- **🧵 Virtual Thread Native** - Built from the ground up for JDK 21+ virtual threads
- **🎯 Pragmatic Design** - No heavy abstractions; close to Java's natural behavior
- **🔀 Composable Effects** - Functional combinators for clean effect composition
- **⚡ Cancellation Built-in** - Interrupt-based cancellation at effect boundaries
- **🎨 Type-Safe Errors** - Explicit error channel: `Effect<E, A>`
- **🔌 Pluggable Runtime** - Swap execution strategies (virtual threads by default)
- **⚙️ Fork/Fiber Support** - Structured concurrency for parallel effect execution
- **🎭 Algebraic Effects** - Capability system for testable, composable side effects

## Installation

### Maven
```xml
<dependency>
    <groupId>com.cajunsystems</groupId>
    <artifactId>roux</artifactId>
    <version>0.1.0</version>
</dependency>
```

### Gradle (Kotlin DSL)
```kotlin
implementation("com.cajunsystems:roux:0.1.0")
```

### Gradle (Groovy)
```groovy
implementation 'com.cajunsystems:roux:0.1.0'
```

**Requirements:** Java 21 or higher

## Quick Example
```java
import com.cajunsystems.roux.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

Effect<IOException, String> readFile = Effect.suspend(() -> 
    Files.readString(Path.of("config.txt"))
);

Effect<IOException, String> withFallback = readFile
    .catchAll(e -> Effect.succeed("default config"))
    .map(String::toUpperCase);

EffectRuntime runtime = DefaultEffectRuntime.create();
String result = runtime.unsafeRun(withFallback);
```

## Core Concepts

### Effects are Lazy

Effects are descriptions of computations, not computations themselves. They only execute when explicitly run.
```java
Effect<Throwable, Integer> effect = Effect.succeed(42)
    .map(x -> x * 2);  // Not executed yet!

// Execution happens here
Integer result = runtime.unsafeRun(effect);
```

### Composable Error Handling

Errors are first-class citizens in the type system.
```java
Effect<IOException, String> readConfig = Effect.suspend(() -> 
    Files.readString(Path.of("config.json"))
);

Effect<IOException, Config> parseConfig = readConfig
    .map(json -> parseJson(json))
    .catchAll(e -> Effect.succeed(Config.DEFAULT));
```

### Concurrent Effects with Fork/Fiber

Run effects concurrently and join their results.
```java
Effect<Throwable, String> fetchUser = Effect.suspend(() -> 
    httpClient.get("/users/123")
);

Effect<Throwable, String> fetchOrders = Effect.suspend(() -> 
    httpClient.get("/orders?user=123")
);

Effect<Throwable, Dashboard> dashboard = fetchUser.fork()
    .flatMap(userFiber -> fetchOrders.fork()
        .flatMap(ordersFiber -> 
            userFiber.join().flatMap(user ->
                ordersFiber.join().map(orders ->
                    new Dashboard(user, orders)
                )
            )
        )
    );
```

### Async Execution with Cancellation

Run effects asynchronously and cancel them when needed.
```java
CancellationHandle handle = runtime.runAsync(
    longRunningEffect,
    result -> System.out.println("Done: " + result),
    error -> System.err.println("Failed: " + error)
);

// Cancel from another thread
handle.cancel();  // Effect stops at next boundary
handle.await();   // Wait for completion
```

### Error Recovery and Transformation
```java
Effect<IOException, Data> loadData = Effect.suspend(() -> 
    readFromDatabase()
);

Effect<AppError, Data> transformed = loadData
    .mapError(io -> new AppError("Database error: " + io.getMessage()))
    .orElse(Effect.suspend(() -> readFromCache()));
```

### Generator-Style Effects with Capabilities

Write imperative-looking code that remains pure and testable using algebraic effects.

```java
// Define your capabilities
sealed interface LogCapability<R> extends Capability<R> {
    record Info(String message) implements LogCapability<Void> {}
}

sealed interface HttpCapability<R> extends Capability<R> {
    record Get(String url) implements HttpCapability<String> {}
}

// Use them in generator-style effects
Effect<Throwable, String> workflow = Effect.generate(ctx -> {
    ctx.perform(new LogCapability.Info("Starting workflow"));
    
    String data = ctx.perform(new HttpCapability.Get("https://api.example.com/data"));
    
    ctx.perform(new LogCapability.Info("Received: " + data));
    
    return data.toUpperCase();
}, handler);

// Swap handlers for testing - no mocking needed!
TestHandler testHandler = new TestHandler()
    .withHttpResponse("https://api.example.com/data", "test-data");

String result = runtime.unsafeRunWithHandler(workflow, testHandler);

// Or use capabilities as effects directly
Effect<Throwable, User> userEffect = new GetUser("123")
    .toEffect()  // Convert capability to effect
    .map(json -> parseJson(json, User.class))
    .retry(3)
    .timeout(Duration.ofSeconds(10));

User user = runtime.unsafeRunWithHandler(userEffect, handler);
```

**Learn more:** [Capabilities Guide](docs/CAPABILITIES.md) | [Capability Recipes](docs/CAPABILITY_RECIPES.md)

## Documentation

- **[Effect API Reference](docs/EFFECT_API.md)** - Complete API documentation with examples
- **[Structured Concurrency Guide](docs/STRUCTURED_CONCURRENCY.md)** - Scoped concurrency patterns and best practices
- **[Capabilities Guide](docs/CAPABILITIES.md)** - Algebraic effects system
- **[Capability Recipes](docs/CAPABILITY_RECIPES.md)** - Common patterns and use cases
- **[Custom Capabilities Example](examples/CustomCapabilities.md)** - Complete working example

## Key Features

### Effect Combinators

- `map` - Transform success values
- `flatMap` - Chain effects sequentially
- `catchAll` - Handle errors and recover
- `mapError` - Transform error types
- `widen` - Widen error type to `Throwable` (safe)
- `narrow` - Narrow error type to specific exception (unsafe cast)
- `orElse` - Fallback to alternative effect
- `attempt` - Convert to `Either<E, A>` for explicit handling
- `zipPar` - Run effects in parallel and combine results

### Structured Concurrency

- `Effect.scoped(body)` - Create a scope for structured concurrency
- `scope.fork(effect)` - Fork effect within scope with automatic cleanup
- `effect.forkIn(scope)` - Convenience method to fork in a scope
- Automatic cancellation on scope exit (success, error, or early return)
- Built on Java's `StructuredTaskScope` (JEP 453)

### Concurrency

- `fork()` - Run effect on a separate virtual thread, returns `Fiber<E, A>`
- `join()` - Wait for forked effect to complete
- `interrupt()` - Cancel a running fiber
- `zipPar(other, combiner)` - Parallel execution with result combination
- `Effects.par()` - Static helpers for 2, 3, 4 parallel effects
- Automatic cancellation at effect boundaries

### Runtime Execution

- `unsafeRun(effect)` - Synchronous execution, throws on error
- `unsafeRunWithHandler(effect, handler)` - Run with capability handler
- `runAsync(effect, onSuccess, onError)` - Asynchronous execution with callbacks
- `CancellationHandle` - Control async execution (cancel, await)

## Getting Started

### Requirements

- JDK 21 or higher (for virtual threads)
- Gradle or Maven

### Installation

**Gradle:**
```groovy
dependencies {
    implementation 'com.cajunsystems:roux:0.1.0'
}
```

**Maven:**
```xml
<dependency>
    <groupId>com.cajunsystems</groupId>
    <artifactId>roux</artifactId>
    <version>0.1.0</version>
</dependency>
```

## Examples

### Simple HTTP Client with Retry
```java
Effect<IOException, String> fetchWithRetry(String url) {
    return Effect.suspend(() -> httpClient.get(url))
        .catchAll(e -> Effect.suspend(() -> {
            Thread.sleep(1000);
            return httpClient.get(url);
        }));
}
```

### Parallel Data Fetching
```java
import static com.cajunsystems.roux.Effects.*;

// Verbose way
Effect<Throwable, Summary> fetchSummary() {
    return users.fork().flatMap(usersF ->
           orders.fork().flatMap(ordersF ->
               usersF.join().flatMap(u ->
                   ordersF.join().map(o ->
                       new Summary(u, o)
                   )
               )
           )
       );
}

// Clean way with zipPar
Effect<Throwable, Summary> fetchSummary() {
    return users.zipPar(orders, Summary::new);
}

// Or with static helper for 3+ effects
Effect<Throwable, Dashboard> fetchDashboard() {
    return par(users, orders, preferences, Dashboard::new);
}
```

### Background Task with Timeout
```java
CancellationHandle handle = runtime.runAsync(
    longTask,
    result -> System.out.println("Completed: " + result),
    error -> System.err.println("Failed: " + error)
);

// Timeout after 5 seconds
if (!handle.await(Duration.ofSeconds(5))) {
    handle.cancel();
    System.out.println("Task timed out");
}
```

## Known Limitations

### Deep Effect Chains

Roux uses recursive execution for effect composition. Very deep effect chains (>1000 nested `flatMap` operations) may cause stack overflow.

**Workaround:** Use loops inside `suspend()` rather than recursive `flatMap` chains:
```java
// ❌ Avoid: Deep recursive chains
Effect<Throwable, Integer> effect = Effect.succeed(0);
for (int i = 0; i < 100000; i++) {
    effect = effect.flatMap(n -> Effect.succeed(n + 1));
}

// ✅ Prefer: Loop inside suspend
Effect<Throwable, Integer> effect = Effect.suspend(() -> {
    int result = 0;
    for (int i = 0; i < 100000; i++) {
        result = result + 1;
    }
    return result;
});
```

**Stack Safety:** Roux uses **trampolined execution** by default, providing true stack safety for arbitrarily deep effect chains. You can chain millions of `flatMap` operations without stack overflow.

## Roadmap

- [x] Core effect system with error channel
- [x] Basic combinators (map, flatMap, catchAll)
- [x] Boundary-based cancellation
- [x] Async execution with CancellationHandle
- [x] Fork/Fiber for concurrent effects
- [x] Algebraic effects via capabilities
- [x] Generator-style effect building
- [x] Scoped structured concurrency
- [x] Stack-safe trampolined execution
- [ ] Retry policies with backoff
- [ ] Resource management (bracket, ensuring)
- [ ] Race and timeout combinators
- [ ] Environment/Layer system for dependency injection

## Related Projects

- **[Cajun](https://github.com/CajunSystems/cajun)** - Actor framework built on Roux and modern Java 21+

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

MIT License - see [LICENSE](LICENSE) file for details

---

<div align="center">
  <sub>Built with ❤️ for the Java community</sub>
</div>