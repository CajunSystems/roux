# Roux — Effect Laws & Test Utilities

## What This Is
A two-part addition to the Roux functional effects library:

1. **Effect Law Tests** — Runnable JUnit proof that `Effect<E,A>` satisfies functor, monad, error channel, and capability composition laws. Makes correctness of the algebra verifiable and discoverable.

2. **Test Utilities** — Practical tooling for writing good tests with Roux effects: fluent Effect assertions, a controllable TestRuntime, and helpers for building CapabilityHandler test doubles.

## Core Value
Roux users can trust the algebra is correct (laws verified) and write clean, expressive effect tests with minimal boilerplate (test utilities).

## Context
Inspired by a talk on correctness of effects and operators — the idea that a well-behaved effects library should be able to *prove* it satisfies mathematical laws, and that those proofs should live as runnable tests, not just documentation.

## Requirements

### Validated (existing)

- ✓ `Effect<E,A>` sealed interface with 10 primitives — existing
- ✓ Functor: `map(f)` — existing
- ✓ Monad: `flatMap(f)`, monadic bind — existing
- ✓ Error channel: `catchAll()`, `mapError()`, `attempt()` — existing
- ✓ `CapabilityHandler` with `orElse()` and `compose()` — existing
- ✓ `DefaultEffectRuntime` with stack-safe trampolining — existing
- ✓ JUnit 5 test infrastructure — existing
- ✓ Zero production dependencies — existing

### Active

- [ ] **Functor law tests**: identity (`map(id) == id`) and composition (`map(f.andThen(g)) == map(f).map(g)`) verified as JUnit tests
- [ ] **Monad law tests**: left identity, right identity, and associativity for `flatMap` verified as JUnit tests
- [ ] **Error channel law tests**: `catchAll` identity, `mapError` composition, `attempt` round-trip laws
- [ ] **Capability law tests**: `CapabilityHandler.orElse()` and `compose()` associativity and identity laws
- [ ] **Fluent Effect assertions**: `EffectAssertions.assertThat(effect).succeedsWith(42)`, `.failsWith(IOException.class)`, `.succeeds()`, `.fails()`
- [ ] **TestRuntime**: controllable clock (manual time advancement), deterministic single-threaded execution, fiber state inspection
- [ ] **Capability test doubles**: `TestCapabilityHandler.builder()` for stub/spy handlers with call tracking, assertion helpers

### Out of Scope

- Property-based testing (jqwik/QuickCheck) — no new dependencies; hand-crafted inputs sufficient for v1
- Separate `roux-test` artifact — test utilities ship in the same library under test scope
- Concurrency law tests (Fork/Race/Scoped) — semantics too complex to formalize cleanly in v1
- Backwards compatibility shims — Java 21+ only

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Law tests as JUnit tests (not docs) | Runnable proof > documentation; catches regressions | Pending |
| Test utilities in same artifact, test scope | No new artifact complexity; users already depend on roux | Pending |
| No new production dependencies | Core constraint — zero-dep production code preserved | Decided |
| Java 21+ only | No backwards compat constraints; use modern Java freely | Decided |
| Hand-crafted law inputs, no property testing | Keeps scope bounded; specific examples are clearer proofs | Decided |

---
*Last updated: 2026-03-10 after initialization*
