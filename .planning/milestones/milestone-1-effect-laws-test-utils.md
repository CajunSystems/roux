# Milestone 1 Archive — Effect Laws & Test Utilities

**Completed:** 2026-03-10
**Phases:** 1–6
**Plans:** 6
**Commits:** ~23

---

## Goal

Prove Roux's algebra is correct via runnable law tests, and provide practical test utilities that reduce boilerplate for effect-based tests.

## Accomplished

- **11 algebraic laws verified** as passing JUnit tests — Functor identity & composition, Monad left identity, right identity & associativity, catchAll identity, mapError identity & composition, attempt round-trip, CapabilityHandler orElse identity & compose associativity
- **`EffectLawSupport`** — observational equivalence utility; runs two effects, compares outcomes (class + message for failures)
- **`EffectAssertions` / `EffectAssert`** — fluent assertion chain: `succeeds()`, `succeedsWith(v)`, `succeedsWith(pred)`, `fails()`, `failsWith(class)`, `failsWith(pred)`, `andReturn()`, `andError()`
- **`TestRuntime` / `TestClock`** — `Sleep` added as first-class `Effect` primitive; `DefaultEffectRuntime.performSleep()` hook lets subclasses intercept; `TestRuntime` advances virtual clock instead of blocking real time
- **`TestCapabilityHandler` / `CapabilityVerifier`** — stub-based handler with call recording; `verify(class).calledOnce()`, `.neverCalled()`, `.calledTimes(n)`, `.calledWith(pred)`

## Phases

| Phase | Name | Plans | Key Deliverable |
|-------|------|-------|-----------------|
| 1 | Law Test Infrastructure | 1 | `EffectLawSupport`, `LawInfrastructureTest` |
| 2 | Functor & Monad Laws | 1 | `FunctorLawsTest` (6 tests), `MonadLawsTest` (9 tests) |
| 3 | Error Channel & Capability Laws | 1 | `ErrorChannelLawsTest` (8 tests), `CapabilityHandlerLawsTest` (6 tests) |
| 4 | Fluent Effect Assertions | 1 | `EffectAssertions`, `EffectAssert`, 22 tests |
| 5 | TestRuntime | 1 | `TestClock`, `TestRuntime`, `Sleep` primitive, 6 tests |
| 6 | Capability Test Doubles | 1 | `TestCapabilityHandler`, `CapabilityVerifier`, 10 tests |

## Key Decisions

- Law tests as JUnit tests (not documentation) — runnable proof catches regressions
- Test utilities in same artifact under test scope — no separate artifact
- No new production dependencies — zero-dep production code preserved
- Java 21+ only — use modern Java freely
- Hand-crafted law inputs — specific examples are clearer proofs than generated ones
- Effect equivalence: run both effects, compare outcomes (not structural equality)
- `EffectLawSupport` is a static utility class — avoids inheritance coupling
- Failure equivalence compares exception class + message (not reference)
- `Sleep` is a first-class `Effect` primitive (not a `Suspend` thunk) — enables runtime interception
- `TestCapabilityHandler` implements `CapabilityHandler<Capability<?>>` directly — no `.widen()` needed
- Sealed interfaces cannot be local classes in Java — must be at class/top level

## Test Count at Completion

~59 tests added across 6 phases (excluding pre-existing tests).

## Out of Scope (deferred)

- Property-based testing (no jqwik/QuickCheck)
- Separate `roux-test` artifact
- Concurrency law tests (Fork/Race/Scoped)
