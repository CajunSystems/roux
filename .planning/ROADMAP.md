# Roadmap — Roux Effect Laws & Test Utilities

## Milestone 1: Effect Laws & Test Utilities ✓ Complete

11 algebraic laws verified, fluent test assertions, virtual-clock TestRuntime, and stub-based capability test doubles. 6 phases, ~59 tests added.

→ Archive: [milestone-1-effect-laws-test-utils.md](milestones/milestone-1-effect-laws-test-utils.md)

---

## Milestone 2: Type-Safe Layer System ✓ Complete

ZIO-style layer mechanism giving compile-time verification that every capability an effect uses has a handler. Phantom types `Empty`/`With<A,B>`, `HandlerEnv<R>`, `EffectWithEnv<R,E,A>`, `Layer<RIn,E,ROut>` with horizontal and vertical composition. 5 phases, 34 tests added. `CapabilityHandler.forType()` promoted as the single recommended builder API.

→ Archive: [milestone-2-type-safe-layer-system.md](milestones/milestone-2-type-safe-layer-system.md)

---

## Milestone 3: PR Review Fixes & v0.3.0 Release Notes

Address all Greptile review findings on PR #6 (CajunSystems/roux#6) and produce the v0.3.0 release artefacts. Two phases: targeted code quality fixes followed by comprehensive release notes with breaking-change migration guidance.

### Phase 12 — Greptile Code Quality Fixes

Fix three P2 code issues identified by Greptile:
1. `HandlerEnv.empty()` — replace per-call anonymous handler allocation with a private static constant
2. `CapabilityHandler.forType()` — add `@SuppressWarnings("unused")` with explanatory comment on the phantom `capabilityType` parameter
3. `DefaultEffectRuntime.performSleep()` — replace `Thread.sleep(duration.toMillis())` with `Thread.sleep(duration)` (Java 19+ API, avoids sub-millisecond truncation)

### Phase 13 — v0.3.0 Release Notes & Migration Guide

Write `RELEASE_NOTES_0.3.0.md` covering:
- New features: `EffectWithEnv`, `HandlerEnv`, `Layer`, `Empty`/`With` phantom types, `TestRuntime`/`TestClock`
- Breaking changes: `Sleep` as new sealed `Effect` subtype (exhaustive switches must add branch), `Builder<F>` now generic (raw-type references break)
- Known limitations: `CapabilityHandler.Builder.build()` walks only direct interfaces (flat sealed hierarchies only; nested hierarchies unsupported)
- Migration checklist for custom `EffectRuntime` implementations and callers that reference `CapabilityHandler.Builder` directly
