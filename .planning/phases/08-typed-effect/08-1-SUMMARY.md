# Summary: 08-1 — EffectWithEnv

## Status: Complete

## What Was Done

Created `EffectWithEnv<R, E, A>` — a thin wrapper around `Effect<E, A>` that statically declares, via phantom type parameter `R`, which capability environment the effect needs. The wrapper has zero runtime overhead beyond the underlying effect, with `R` erased at compile time.

Implemented:
- `EffectWithEnv.of(Effect<E,A>)` — wrap an effect with explicit capability requirements `R`
- `EffectWithEnv.pure(Effect<E,A>)` — wrap an effect with `Empty` requirements (no capabilities)
- `.map(Function<A,B>)` — transform result value, preserving `R`
- `.flatMap(Function<A, EffectWithEnv<R,E,B>>)` — chain effects, preserving `R`
- `.run(HandlerEnv<R>, EffectRuntime)` — execute with compile-time-checked environment
- `.effect()` — access underlying `Effect<E,A>` for untyped API interop

`flatMap` accesses the private `.effect` field of the returned `EffectWithEnv` instance directly — legal in Java (per-class, not per-instance visibility).

Added `EffectWithEnvTest` with 9 tests covering all methods and the combined `With<StoreOps, LogOps>` environment scenario.

## Commits

- f6a95d3 — feat(08-1): add EffectWithEnv typed effect wrapper
- 46cc2bf — test(08-1): add EffectWithEnvTest with 9 tests

## Test Results

```
EffectWithEnvTest — 9 tests, 0 failures, 0 skipped

  map_preservesCapabilityRequirement()       PASSED
  of_withCombinedEnv_handlesBothCapabilities() PASSED
  flatMap_chainsEffects()                    PASSED
  pure_failingEffect_propagatesError()       PASSED
  of_putCapability_mutatesState()            PASSED
  pure_runsWithEmptyEnv()                    PASSED
  of_runsCapabilityEffectWithMatchingEnv()   PASSED
  effect_returnsUnderlyingEffect()           PASSED
  map_transformsResultValue()                PASSED

Full suite: BUILD SUCCESSFUL
```

## Ready For

Phase 9: Layer Core — Layer<RIn, E, ROut> with succeed(), fromEffect(), build()
