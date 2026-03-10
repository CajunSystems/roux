# Phase 10, Plan 1: Layer Composition — Summary

## Objective Achieved

Added horizontal (`and`) and vertical (`andProvide`) composition operators to `Layer`, enabling multiple layers to be combined into a single layer that satisfies all requirements. All 4 tests pass; no regressions in the full test suite.

---

## Tasks

| Task | Description | Commit |
|------|-------------|--------|
| 1 | Add `widenError` private helper to `Layer` | `eba2d8e` |
| 2 | Add `Layer.and()` horizontal composition | `31ec5d4` |
| 3 | Add `Layer.andProvide()` vertical composition | `c81041f` |
| 4 | Write `LayerCompositionTest.java` | `2fe2f40` |

---

## Deviations from Plan

- **Task 4**: The plan provided a test skeleton using `new DefaultEffectRuntime()` (no-arg constructor). The actual constructor requires `(ExecutorService, boolean)` arguments; `DefaultEffectRuntime.create()` is the correct factory. The test was written using `DefaultEffectRuntime.create()` and `runtime.close()` in `@AfterEach`, matching the pattern used in `LayerTest.java`.
- **Task 4**: Removed the `EffectRuntime` interface import (unused after using `DefaultEffectRuntime` directly) and added `AfterEach` import.

---

## Key Decisions

- `widenError` is a `private static` method in the interface — legal in Java 9+ and does not break the `@FunctionalInterface` contract (only the SAM `build()` counts).
- Both `and()` and `andProvide()` return `Layer<RIn, Throwable, With<ROut, S>>` — widening both error types to `Throwable` is the correct approach given Java's lack of union types for checked exceptions.
- The unchecked cast in `widenError` is safe at runtime because of type erasure; the `@SuppressWarnings("unchecked")` is correctly scoped to that helper only.
