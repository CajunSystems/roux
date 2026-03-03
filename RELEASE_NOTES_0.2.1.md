# Roux 0.2.1 - Scoped Fork Context Fixes and Better Diagnostics

Roux **0.2.1** is a bugfix release focused on structured-concurrency correctness and clearer capability error reporting.

## ✨ Highlights

### ✅ Scoped fork now preserves capability context

- `scope.fork(...)` now inherits the current `ExecutionContext`
- Forked effects now see the same installed `CapabilityHandler` as the parent flow
- Behavior is now consistent with `effect.fork()`

### ✅ Missing capability errors are now actionable

- Missing handler failures now include the exact capability type that was attempted
- Added a dedicated `MissingCapabilityHandlerException` for cleaner typed handling
- Error guidance points users to `unsafeRunWithHandler(...)` or embedded generator handlers

### ✅ Cleaner failure propagation from fibers

- `Fiber.join()` no longer double-wraps runtime exceptions
- Stack traces are easier to read and root causes are easier to identify

## 🧪 Testing

- Added scoped-fork regression tests for:
  - capability-handler inheritance inside scoped forks
  - missing-handler diagnostics including capability type
- Existing scope-related tests continue to pass

## 📦 Installation

### Maven

```xml
<dependency>
    <groupId>com.cajunsystems</groupId>
    <artifactId>roux</artifactId>
    <version>0.2.1</version>
</dependency>
```

### Gradle (Kotlin DSL)

```kotlin
implementation("com.cajunsystems:roux:0.2.1")
```

### Gradle (Groovy)

```groovy
implementation 'com.cajunsystems:roux:0.2.1'
```

## 🔗 Links

- **GitHub**: [github.com/CajunSystems/roux](https://github.com/CajunSystems/roux)
- **Maven Central**: [search.maven.org/artifact/com.cajunsystems/roux/0.2.1/jar](https://search.maven.org/artifact/com.cajunsystems/roux/0.2.1/jar)
- **Documentation**: [github.com/CajunSystems/roux/tree/main/docs](https://github.com/CajunSystems/roux/tree/main/docs)
- **Changelog**: [github.com/CajunSystems/roux/blob/main/CHANGELOG.md](https://github.com/CajunSystems/roux/blob/main/CHANGELOG.md)

---

**Full Changelog**: [v0.2.0...v0.2.1](https://github.com/CajunSystems/roux/compare/v0.2.0...v0.2.1)
