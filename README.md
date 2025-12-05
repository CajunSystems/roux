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
- **⚡ Cancellation Built-in** - Interrupt-based cancellation with structured concurrency
- **🔁 Retry Support** - First-class retry policies with backoff strategies
- **🎨 Type-Safe Errors** - Explicit error channel: `Effect<E, A>`
- **🔌 Pluggable Runtime** - Swap execution strategies (virtual threads by default)

## Quick Example
```java
import roux.Effect;
import roux.EffectRuntime;

Effect<IOException, String> readFile = Effect.suspend(() -> 
    Files.readString(Path.of("config.txt"))
);

Effect<IOException, String> withFallback = readFile
    .catchAll(e -> Effect.succeed("default config"))
    .map(String::toUpperCase);

EffectRuntime runtime = EffectRuntime.defaultRuntime();
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
Effect<DatabaseError, User> getUser(String id) {
    return queryDatabase(id)
        .mapError(SqlException::toDatabaseError)
        .catchAll(error -> Effect.fail(new UserNotFound(id)));
}
```

### Automatic Cancellation
Effects check for cancellation at composition boundaries—no manual checkpoint insertion needed.
```java
CancellationHandle handle = runtime.runAsync(longRunningEffect, 
    result -> System.out.println("Done: " + result),
    error -> System.err.println("Failed: " + error)
);

// Cancel from another thread
handle.cancel();  // Effect stops at next boundary
```

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

## Roadmap

- [x] Core effect system with error channel
- [x] Basic combinators (map, flatMap, catchAll)
- [x] Boundary-based cancellation
- [ ] Async execution with CancellationHandle
- [ ] Fork/Fiber for concurrent effects
- [ ] Scoped structured concurrency
- [ ] Retry policies with backoff
- [ ] Resource management (bracket, ensuring)
- [ ] Environment/Layer system for dependency injection

## Related Projects

- **[Cajun](https://github.com/CajunSystems/cajun)** - Actor framework built on modern java 21+

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

MIT License - see [LICENSE](LICENSE) file for details

---

<div align="center">
  <sub>Built with ❤️ for the Java community</sub>
</div>