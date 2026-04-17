# Phase 11, Plan 1 — Summary

## Objective
Finalize F-bounded Builder.on() and write end-to-end LayerIntegrationTest.

## Tasks Completed

### Task 1: F-bounded Builder.on()
- **Commit:** 5c883fc
- Attempted intersection bound `C extends F & Capability<R>` on `Builder.on()` — blocked by JLS §4.4: when the first bound of a type parameter is itself a type variable (F), no additional bounds are permitted. This is a Java compiler restriction with no workaround short of changing Builder's structure.
- The existing `C extends F` already transitively enforces the Capability bound since `Builder<F extends Capability<?>>` — the F-bound still holds.
- Also committed user's pre-existing `forType()` factory and `Builder<F>` generics
- Updated `docs/CAPABILITIES.md` with forType() documentation
- All existing tests pass — no regressions

### Task 2: LayerIntegrationTest
- **Commit:** 346a4eb
- 5 end-to-end tests: horizontal composition, vertical composition, 3-capability nesting, HandlerEnv.of(), pure effect
- Minor deviation: field declared as `DefaultEffectRuntime` (not `EffectRuntime`) since `close()` is only on the concrete class, not the interface
- All tests pass

## Deviations
- Task 1: `<R, C extends F & Capability<R>>` signature is not valid Java (JLS §4.4 forbids additional bounds when first bound is a type variable). The existing `C extends F` signature is retained; the F-bound is already in place via `Builder<F extends Capability<?>>`.
- Task 2: `runtime` field typed as `DefaultEffectRuntime` instead of `EffectRuntime` — `close()` is not part of the `EffectRuntime` interface.

## Key Outcomes
- Milestone 2 (Type-Safe Layer System) is complete
- The F-bound on `Builder<F extends Capability<?>>` enforces that all registered handlers cover a capability family; individual handler return type alignment is checked at the `HandlerEnv.of()` level via its own `<R, C extends Capability<R>>` bound
- Full workflow verified: Layer → Env → EffectWithEnv end-to-end
