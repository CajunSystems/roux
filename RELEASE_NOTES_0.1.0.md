# Roux 0.1.0 - Initial Release

We're excited to announce the first release of **Roux** - a modern effect system for Java 21+ that brings composable, type-safe effects to the JVM!

## 🎉 What is Roux?

Roux is a lightweight, pragmatic effect system built from the ground up for Java 21+ virtual threads. It provides a clean, composable way to handle side effects while staying close to Java's natural behavior.

## ✨ Key Features

### 🧵 Virtual Thread Native
Built specifically for JDK 21+ virtual threads with structured concurrency support.

### 🎯 Core Effect System
- **Type-safe effects** with explicit error channel: `Effect<E, A>`
- **Pure values** - `Effect.succeed()` for wrapping values
- **Failures** - `Effect.fail()` for explicit error handling
- **Lazy evaluation** - `Effect.suspend()` for deferred computations

### 🔀 Rich Combinators
- `map` - Transform success values
- `flatMap` - Chain effects sequentially (monadic composition)
- `catchAll` - Handle and recover from errors
- `mapError` - Transform error types
- `orElse` - Fallback to alternative effect on failure
- `attempt` - Convert effect to `Either<E, A>`
- `fold` - Handle both success and error cases
- `widen`/`narrow` - Type-safe error type transformations

### ⚡ Concurrency & Parallelism
- **Fork/Fiber** - Launch effects concurrently
- **join** - Wait for fiber completion
- **interrupt** - Cancel running fibers
- **zipPar** - Run two effects in parallel
- **Effects.par** - Run multiple effects in parallel

### 🏗️ Structured Concurrency
- **Effect.scoped** - Create structured concurrency scopes
- **Automatic cancellation** - All forked effects cancelled on scope exit
- Built on Java 21's `StructuredTaskScope` (JEP 453)

### 🎭 Algebraic Effects (Capabilities)
- **Capability system** - Define custom algebraic effects
- **CapabilityHandler** - Handle capabilities with custom interpreters
- **Type-safe composition** - Compose handlers safely

### 🎨 Generator-Style Effects
- **Effect.generate** - Build effects using imperative-style generators
- **GeneratorContext** - Imperative API for effect building
  - `perform` - Execute capabilities
  - `yield` - Embed effects
  - `call` - Execute throwing operations

### 🛡️ Stack Safety
- **Trampolined execution** - Stack-safe by default
- **Constant stack depth** - Handle millions of `flatMap` operations without stack overflow
- **Enabled by default** - No configuration needed

## 📦 Installation

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

## 🚀 Quick Example

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

## 📚 Documentation

- [Effect API Reference](docs/EFFECT_API.md)
- [Structured Concurrency Guide](docs/STRUCTURED_CONCURRENCY.md)
- [Capabilities Guide](docs/CAPABILITIES.md)
- [Capability Recipes](docs/CAPABILITY_RECIPES.md)
- [Custom Capabilities Example](examples/CustomCapabilities.md)

## 🧪 Testing

This release includes 100+ unit tests covering:
- All effect combinators
- Stack safety (chains up to 1,000,000 operations)
- Concurrency and fork/fiber behavior
- Structured concurrency scopes
- Capability system
- Generator-style effects

## 🔗 Links

- **GitHub**: https://github.com/CajunSystems/roux
- **Maven Central**: https://search.maven.org/artifact/com.cajunsystems/roux/0.1.0/jar
- **Documentation**: https://github.com/CajunSystems/roux/tree/main/docs

## 🙏 Acknowledgments

Roux is inspired by modern effect systems like ZIO and Cats Effect, adapted for Java's unique strengths with virtual threads and structured concurrency.

## 📝 License

MIT License - see [LICENSE](LICENSE) file for details.

---

**Full Changelog**: https://github.com/CajunSystems/roux/blob/main/CHANGELOG.md
