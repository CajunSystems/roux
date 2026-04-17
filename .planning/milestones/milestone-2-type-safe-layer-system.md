# Milestone 2 Archive — Type-Safe Layer System

**Completed:** 2026-03-10
**Phases:** 7–11
**Plans:** 5
**Commits:** ~35

---

## Goal

Extend the capability system with a ZIO-style layer mechanism that gives compile-time verification that every capability an effect uses has a handler — no runtime `UnsupportedOperationException` from a forgotten handler; the program won't compile.

## Accomplished

- **`Empty` / `With<A,B>`** phantom marker interfaces — compile-time type-level sets; never instantiated, zero runtime cost
- **`HandlerEnv<R>`** — typed wrapper around `CapabilityHandler<Capability<?>>` with F-bounded `.of()`, `.and()`, `.fromHandler()`, `.empty()`, `.toHandler()`
- **`EffectWithEnv<R,E,A>`** — thin wrapper around `Effect<E,A>` declaring capability requirements; `.run(HandlerEnv<R>, runtime)` only compiles when env covers `R`
- **`Layer<RIn,E,ROut>`** — `@FunctionalInterface` recipe that builds `HandlerEnv<ROut>` from `HandlerEnv<RIn>`; `Layer.succeed()` (leaf) and `Layer.fromEffect()` (dependency-injected)
- **`Layer.and()`** — horizontal composition: same input, merged output `With<ROut,S>`
- **`Layer.andProvide()`** — vertical composition: this layer's output feeds next layer's input; both retained in `With<ROut,S>`
- **`CapabilityHandler.forType()`** promoted to sole recommended builder API; `builder()` deprecated since v0.3.0
- **`LayerIntegrationTest`** — 5 end-to-end tests covering the full Layer → Env → EffectWithEnv workflow
- **`docs/TYPED_EFFECTS.md`** — comprehensive guide covering all new APIs, composition patterns, API reference tables, and Java vs ZIO design notes

## Phases

| Phase | Name | Plans | Key Deliverable |
|-------|------|-------|-----------------|
| 7 | Phantom Types + HandlerEnv | 1 | `Empty`, `With`, `HandlerEnv`, 9 tests |
| 8 | EffectWithEnv | 1 | `EffectWithEnv`, 9 tests |
| 9 | Layer Core | 1 | `Layer` interface, `succeed()`, `fromEffect()`, 7 tests |
| 10 | Layer Composition | 1 | `Layer.and()`, `Layer.andProvide()`, 4 tests |
| 11 | F-Bounded Builder + Integration | 1 | `forType()` as primary API, `builder()` deprecated, 5 integration tests, full docs |

## Key Decisions

- `EffectWithEnv<R,E,A>` is a thin wrapper — `Effect<E,A>` production API unchanged
- Phantom types `Empty`/`With<A,B>` are never instantiated — compile-time only
- `HandlerEnv.and()` returns `HandlerEnv<With<R,S>>` — right-nested by convention
- `HandlerEnv.of()` phantom type is `C` (the capability family), not `R` (the return type)
- `EffectWithEnv.of()` R is phantom — caller declares it explicitly; Java cannot infer phantom types
- `Layer<RIn,E,ROut>` is a `@FunctionalInterface` — `build()` is the SAM
- `Layer.succeed()` returns `Layer<Empty, RuntimeException, C>` — leaf layer
- `Layer.and()` and `Layer.andProvide()` widen both error types to `Throwable` — Java has no union types for checked exceptions; `widenError` uses a safe unchecked cast (erased at runtime)
- `C extends F & Capability<R>` intersection bound on `Builder.on()` is not valid Java (JLS §4.4 forbids additional bounds when first bound is a type variable); existing `C extends F` retains the F-bound via `Builder<F extends Capability<?>>`
- `CapabilityHandler.builder()` deprecated since v0.3.0 — `forType()` is the recommended API
- `HandlerEnv.of()` uses `forType()` internally to avoid triggering deprecation warnings in production code
- `DefaultEffectRuntime.create()` is the correct factory — no-arg constructor does not exist; tests need `@AfterEach runtime.close()`
- `HandlerEnv.empty()` uses an anonymous inner class — Java cannot infer the generic type for a lambda assigned to `CapabilityHandler<Capability<?>>`

## Test Count at Completion

34 tests added across 5 phases.

## Java vs ZIO Divergence

| ZIO (Scala) | Roux (Java) |
|---|---|
| `ZIO[R, E, A]` — R in the core effect type | `EffectWithEnv<R,E,A>` — thin wrapper; `Effect<E,A>` unchanged |
| Intersection types `A with B with C` | Nested `With<A, With<B, C>>` phantom interfaces |
| Implicit `ZLayer` wiring | Explicit `.and()` / `.andProvide()` composition |
| Compiler synthesises proofs | Caller assembles `HandlerEnv` manually |

All type safety from ordinary Java generics — no annotation processing, no code generation.
