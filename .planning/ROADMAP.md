# Roadmap — Roux Effect Laws & Test Utilities

## Milestone 1: Effect Laws & Test Utilities ✓ Complete

11 algebraic laws verified, fluent test assertions, virtual-clock TestRuntime, and stub-based capability test doubles. 6 phases, ~59 tests added.

→ Archive: [milestone-1-effect-laws-test-utils.md](milestones/milestone-1-effect-laws-test-utils.md)

---

## Milestone 2: Type-Safe Layer System ✓ Complete

ZIO-style layer mechanism giving compile-time verification that every capability an effect uses has a handler. Phantom types `Empty`/`With<A,B>`, `HandlerEnv<R>`, `EffectWithEnv<R,E,A>`, `Layer<RIn,E,ROut>` with horizontal and vertical composition. 5 phases, 34 tests added. `CapabilityHandler.forType()` promoted as the single recommended builder API.

→ Archive: [milestone-2-type-safe-layer-system.md](milestones/milestone-2-type-safe-layer-system.md)

---

## Next Milestone

Not yet defined.
