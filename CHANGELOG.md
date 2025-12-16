# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

[0.1.0]: https://github.com/CajunSystems/roux/releases/tag/v0.1.0
