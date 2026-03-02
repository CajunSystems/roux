# Roux 0.2.0 - Reliability, Resource Safety, and New Core Combinators

We're excited to announce **Roux 0.2.0** - a major update focused on runtime reliability, better resource safety, richer effect combinators, and improved ergonomics across the API.

## ✨ Highlights

### ✅ New Effect Constructors

- `Effect.unit()`
- `Effect.runnable(Runnable)`
- `Effect.sleep(Duration)`
- `Effect.when(boolean, Effect)`
- `Effect.unless(boolean, Effect)`

### ✅ New Combinators

- `tap(Consumer<A>)`
- `tapError(Consumer<E>)`
- `retry(int)`
- `retryWithDelay(int, Duration)`
- `retry(RetryPolicy)`
- `timeout(Duration)`

### ✅ New Concurrency Helpers (`Effects`)

- `Effects.race(List)` / `Effects.race(ea, eb)`
- `Effects.sequence(List)`
- `Effects.traverse(List, Function)`
- `Effects.parAll(List)`

### ✅ Resource Management

- New `Resource<A>` type with:
  - `Resource.make(acquire, release)`
  - `Resource.fromCloseable(acquire)`
  - `resource.use(f)`
  - `Resource.ensuring(effect, finalizer)`

### ✅ Retry Policies

- New `RetryPolicy` with fluent composition:
  - `immediate()`
  - `fixed(Duration)`
  - `exponential(Duration)`
  - `.maxAttempts(n)`
  - `.maxDelay(Duration)`
  - `.withJitter(factor)`
  - `.retryWhen(Predicate<Throwable>)`

### ✅ Runtime and Capability Improvements

- `DefaultEffectRuntime` now implements `AutoCloseable`
- Async and fork execution now use trampolined interpretation for stack safety
- Replaced spin-wait startup synchronization with `CountDownLatch`
- Improved capability dispatch semantics and handler composition behavior

## 🛠️ Fixes

- Closed stack-safety gaps in `runAsync` and `executeFork`
- Removed CPU-burning spin-wait loops in async/fork startup
- Fixed capability composition fall-through behavior to avoid swallowing internal handler errors
- Fixed interface-resolution edge cases in `CompositeCapabilityHandler`
- Corrected docs drift where `.retry()` and `.timeout()` were previously documented but missing
- Updated Java-idiomatic tuple accessor names (`first()`, `second()`, `third()`)

## 🧪 Testing

- **205 total tests** (up from ~100 in 0.1.0)
- New dedicated test coverage for:
  - combinators (`tap`, retry variants, timeout, conditional effects)
  - collection helpers (`sequence`, `traverse`, `parAll`, `race`)
  - `Either` enriched API
  - capability handler builder/composition
  - retry policy validation + integration
  - full `Resource` lifecycle semantics

## 📦 Installation

### Maven

```xml
<dependency>
    <groupId>com.cajunsystems</groupId>
    <artifactId>roux</artifactId>
    <version>0.2.0</version>
</dependency>
```

### Gradle (Kotlin DSL)

```kotlin
implementation("com.cajunsystems:roux:0.2.0")
```

### Gradle (Groovy)

```groovy
implementation 'com.cajunsystems:roux:0.2.0'
```

## 🔗 Links

- **GitHub**: [github.com/CajunSystems/roux](https://github.com/CajunSystems/roux)
- **Maven Central**: [search.maven.org/artifact/com.cajunsystems/roux/0.2.0/jar](https://search.maven.org/artifact/com.cajunsystems/roux/0.2.0/jar)
- **Documentation**: [github.com/CajunSystems/roux/tree/main/docs](https://github.com/CajunSystems/roux/tree/main/docs)
- **Changelog**: [github.com/CajunSystems/roux/blob/main/CHANGELOG.md](https://github.com/CajunSystems/roux/blob/main/CHANGELOG.md)

---

**Full Changelog**: [v0.1.0...v0.2.0](https://github.com/CajunSystems/roux/compare/v0.1.0...v0.2.0)
