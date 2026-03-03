# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.2.1] - 2026-03-03

### Fixed
- `scope.fork(...)` now inherits the current `ExecutionContext` (including installed capability handler), making it semantically consistent with `effect.fork()`
- Missing capability-handler failures now include the concrete capability type that was attempted
- `Fiber.join()` no longer double-wraps runtime exceptions, improving error readability

### Testing
- Added scoped-fork regression coverage to verify capability-handler propagation and actionable missing-handler diagnostics

## [0.2.0] - 2026-03-01

### Added

#### New Effect Constructors
- **`Effect.unit()`** — Create an effect that succeeds with `Unit`
- **`Effect.runnable(Runnable)`** — Wrap a `Runnable` as an effect (lazy, succeeds with `Unit`)
- **`Effect.sleep(Duration)`** — Sleep for a given duration as an effect (interruptible)
- **`Effect.when(boolean, Effect)`** — Run effect only when condition is `true`
- **`Effect.unless(boolean, Effect)`** — Run effect only when condition is `false`

#### New Effect Combinators
- **`tap(Consumer<A>)`** — Execute a side-effect on success without changing the value
- **`tapError(Consumer<E>)`** — Execute a side-effect on failure without changing the error
- **`retry(int)`** — Retry on failure up to N additional times (immediate, no delay)
- **`retryWithDelay(int, Duration)`** — Retry with a fixed pause between attempts
- **`retry(RetryPolicy)`** — Declarative retry with configurable backoff, jitter, and per-error predicates
- **`timeout(Duration)`** — Fail with `TimeoutException` if not completed within the duration

#### New Effect Primitives
- **`Effect.Timeout`** — Primitive node powering the `timeout()` combinator
- **`Effect.Race`** — Primitive node powering `Effects.race()`

#### New Concurrency Helpers (`Effects`)
- **`Effects.race(List)`** / **`Effects.race(ea, eb)`** — Return the result of whichever effect completes first
- **`Effects.sequence(List)`** — Run a list of effects sequentially and collect results
- **`Effects.traverse(List, Function)`** — Map each element to an effect and run sequentially
- **`Effects.parAll(List)`** — Run a list of effects in parallel and collect results

#### Resource Management
- **`Resource<A>`** — Managed resource type guaranteeing release on success, failure, and cancellation
- **`Resource.make(acquire, release)`** — Acquire a resource with a paired release action
- **`Resource.fromCloseable(acquire)`** — Wrap any `AutoCloseable` as a `Resource`
- **`resource.use(f)`** — Acquire, apply a function, and always release; original errors are preserved if release also fails
- **`Resource.ensuring(effect, finalizer)`** — Run a finalizer after any effect (try-finally equivalent); finalizer errors are suppressed

#### Retry Policies
- **`RetryPolicy`** — Declarative retry strategy with a fluent builder API
- **`RetryPolicy.immediate()`** — Retry with no delay between attempts
- **`RetryPolicy.fixed(Duration)`** — Constant delay between retries
- **`RetryPolicy.exponential(Duration)`** — Delay doubles on each attempt (base, base×2, base×4, …)
- **`.maxAttempts(n)`** — Cap the number of retry attempts
- **`.maxDelay(Duration)`** — Cap the computed delay (useful with exponential backoff)
- **`.withJitter(factor)`** — Add ±factor random spread to delays to avoid thundering herds
- **`.retryWhen(Predicate<Throwable>)`** — Only retry when the predicate matches the error

#### Enriched Data Types
- **`Either.map(f)`** — Transform the right value
- **`Either.flatMap(f)`** — Chain on the right value
- **`Either.mapLeft(f)`** — Transform the left value
- **`Either.fold(onLeft, onRight)`** — Collapse both sides into a single value
- **`Either.getOrElse(default)`** — Return right value or a default
- **`Either.getOrElse(Function)`** — Return right value or compute from left
- **`Either.isLeft()`** / **`Either.isRight()`** — Predicate helpers
- **`Either.swap()`** — Flip left and right

#### Capability System Improvements
- **`CapabilityHandler.builder()`** — Fluent, type-safe handler builder using lambda-friendly `ThrowingFunction` (replaces subclassing)
- **`CapabilityHandler.compose()`** — Fixed to use `UnsupportedOperationException` for "not-handled" dispatch instead of `ClassCastException`
- **`CapabilityHandler.orElse()`** — Fixed to use the same `UnsupportedOperationException` convention
- **`CompositeCapabilityHandler`** — Fixed `findCapabilityInterface` to use BFS and correctly handle classes that implement multiple `Capability` sub-interfaces

#### Runtime Improvements
- **`DefaultEffectRuntime implements AutoCloseable`** — Runtime can now be used in try-with-resources; shuts down the executor service cleanly
- **Stack safety for async paths** — `runAsync` and `executeFork` now use the trampolined interpreter (previously they used the recursive path, making deeply-chained forked effects potentially unsafe)
- **No CPU spin-wait** — `runAsync` and `executeFork` now use `CountDownLatch` instead of busy-spinning on `Thread.onSpinWait()` to wait for the worker thread to start

### Fixed
- `runAsync` used the non-trampolined `execute()` path, meaning deeply-nested async effect chains could still stack-overflow
- `executeFork` had the same stack-safety gap as `runAsync`
- Both `runAsync` and `executeFork` busy-spun in a loop (`while (threadRef.get() == null) Thread.onSpinWait()`) burning CPU while waiting for virtual threads to start
- `CapabilityHandler.compose()` silently swallowed `ClassCastException`s from bugs inside handlers — now only `UnsupportedOperationException` is interpreted as "capability not handled"
- `CapabilityHandler.orElse()` had the same ClassCastException footgun
- `CompositeCapabilityHandler` only found the first implementing interface; now resolves the correct handler via BFS over the full interface hierarchy
- Documentation in `CAPABILITIES.md` referenced `.retry()` and `.timeout()` methods that did not exist in 0.1.0 — both are now implemented
- The resource-management example in `EFFECT_API.md` forked cleanup incorrectly (cleanup could run concurrently with main work); replaced with correct try-finally and scoped patterns
- `Tuple2`/`Tuple3` accessors renamed from `_1()`/`_2()` to `first()`/`second()`/`third()` for Java-idiomatic naming

### Testing
- 205 total tests (up from ~100 in 0.1.0)
- New test class `EffectCombinatorTest` — covers tap, tapError, retry, retryWithDelay, timeout, unit, runnable, sleep, when, unless
- New test class `EffectsCollectionsTest` — covers sequence, traverse, parAll, race
- New test class `EitherTest` — covers all new Either methods
- New test class `CapabilityHandlerBuilderTest` — covers builder(), compose(), orElse(), CompositeCapabilityHandler
- New test class `RetryPolicyTest` — covers all RetryPolicy variants, validation, and Effect.retry(RetryPolicy) integration
- New test class `ResourceTest` — covers acquire/release lifecycle, nesting, ensuring, and error-preservation semantics

---

## [0.1.0] - 2024-12-16

### Added

#### Core Effect System
- **Effect type** - Sealed interface for composable, type-safe effects with error channel
- **Pure values** - `Effect.succeed()` for wrapping pure values
- **Failures** - `Effect.fail()` for explicit error handling
- **Suspended computations** - `Effect.suspend()` for lazy evaluation

#### Effect Combinators
- **map** - Transform success values
- **flatMap** - Chain effects sequentially (monadic composition)
- **catchAll** - Handle and recover from errors
- **mapError** - Transform error types
- **orElse** - Fallback to alternative effect on failure
- **attempt** - Convert effect to `Either<E, A>` for explicit error handling
- **fold** - Handle both success and error cases
- **widen** - Safely widen error type to `Throwable`
- **narrow** - Unsafely narrow error type (with type refinement)

#### Concurrency
- **Fork/Fiber** - Launch effects concurrently with `fork()` and `Fiber` abstraction
- **join** - Wait for fiber completion
- **interrupt** - Cancel running fibers
- **zipPar** - Run two effects in parallel and combine results
- **Effects.par** - Run multiple effects in parallel

#### Structured Concurrency
- **Effect.scoped** - Create structured concurrency scopes with automatic cleanup
- **scope.fork** - Fork effects within a scope with guaranteed cancellation
- **forkIn** - Convenience method to fork in a given scope
- **Automatic cancellation** - All forked effects cancelled on scope exit (success, error, or early return)
- Built on Java 21's `StructuredTaskScope` (JEP 453)

#### Algebraic Effects (Capabilities)
- **Capability system** - Define custom algebraic effects
- **CapabilityHandler** - Handle capabilities with custom interpreters
- **Effect.from** - Lift capabilities into effects
- **unsafeRunWithHandler** - Run effects with capability handlers
- **widen** - Type-safe capability handler composition

#### Generator-Style Effects
- **Effect.generate** - Build effects using imperative-style generators
- **GeneratorContext** - Context for generator-style effect building
  - `perform` - Execute capabilities
  - `yield` - Embed effects
  - `call` - Execute throwing operations
  - `lift` - Lift capabilities to effects

#### Stack Safety
- **Trampolined execution** - Stack-safe execution for arbitrarily deep effect chains
- **Constant stack depth** - Handle millions of `flatMap` operations without stack overflow
- **Enabled by default** - All effects benefit from stack safety automatically
- **Continuation-based** - Efficient continuation stack for pending operations

#### Runtime
- **DefaultEffectRuntime** - Default runtime implementation with virtual threads
- **unsafeRun** - Execute effects synchronously
- **runAsync** - Execute effects asynchronously with callbacks
- **CancellationHandle** - Handle for cancelling async operations
- **Boundary-based cancellation** - Automatic cancellation checkpoints

#### Type Safety
- **Typed error channel** - Errors tracked at type level
- **Compile-time safety** - Effect composition checked by compiler
- **No runtime surprises** - All effects explicit in types

### Documentation
- Comprehensive Effect API reference
- Structured Concurrency guide with patterns and best practices
- Capabilities guide for algebraic effects
- Capability recipes with common patterns
- Custom capabilities example
- Complete README with examples and feature overview

### Testing
- 100+ unit tests covering all features
- Stack safety tests with chains up to 1,000,000 operations
- Concurrency tests for fork/fiber behavior
- Structured concurrency scope tests
- Capability system tests
- Generator-style effect tests

[0.2.1]: https://github.com/CajunSystems/roux/releases/tag/v0.2.1
[0.2.0]: https://github.com/CajunSystems/roux/releases/tag/v0.2.0
[0.1.0]: https://github.com/CajunSystems/roux/releases/tag/v0.1.0
