# Summary: 09-1 — Layer Core

## Status: Complete

## What Was Done

Created `Layer<RIn, E, ROut>` as a `@FunctionalInterface` in the `com.cajunsystems.roux.capability` package. The interface has a single abstract method `build(HandlerEnv<RIn>)` returning `Effect<E, HandlerEnv<ROut>>`, plus two static factory methods:

- `Layer.succeed(Class<C>, ThrowingFunction<C, R>)` — creates a leaf layer with no dependencies, returning `Layer<Empty, RuntimeException, C>`
- `Layer.fromEffect(Class<C>, Function<HandlerEnv<RIn>, Effect<E, ThrowingFunction<C, R>>>)` — creates a layer whose handler is produced by running an effect (enabling dependency injection during construction)

The `fromEffect` implementation uses a local variable for `HandlerEnv.of(type, h)` to assist Java type inference inside the lambda, as noted in the plan.

No deviations from the plan. All 7 tests pass.

## Commits

- cd3c478 — feat(09-1): add Layer functional interface with succeed and fromEffect factories
- 83ce084 — test(09-1): add LayerTest with 7 tests

## Test Results

```
BUILD SUCCESSFUL in 883ms
```

LayerTest: 7 tests — all passed
Full suite: BUILD SUCCESSFUL in 30s — all tests passed

## Ready For

Phase 10: Layer Composition — and() horizontal + andProvide() vertical operators
