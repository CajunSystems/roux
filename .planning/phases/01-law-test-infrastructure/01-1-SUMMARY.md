# Summary: 01-1 — Law Test Infrastructure

## Status: Complete

## What Was Done

Created the foundational infrastructure for algebraic law testing in Roux.

**`EffectLawSupport`** (`com.cajunsystems.roux.laws`) — a static utility class providing observational effect equivalence assertions:
- `assertEquivalent(lawName, left, right)` — runs both effects with a fresh `DefaultEffectRuntime`, compares outcomes
- `assertEquivalentWith(lawName, left, right, runtime)` — same but accepts a provided runtime
- Internal `Outcome<A>` sealed type (`Success`/`Failure`) captures results without throwing
- Success comparison: `assertEquals(value, value)`
- Failure comparison: class equality + message equality (since exceptions use reference equality)
- Error messages include the law name for readable test failures

**`LawInfrastructureTest`** — 6 smoke tests validating the utility itself:
- 3 positive cases: same value, same error, chained effects producing same result
- 3 negative cases: different values, success vs failure, different error messages

**Design decision confirmed:** Static utility class (not abstract base) — avoids inheritance coupling, keeps law test classes standalone and readable.

## Commits

- `6ed1dbd` — `feat(01-1): add EffectLawSupport utility with observational equivalence assertions`
- `f9f3228` — `test(01-1): add LawInfrastructureTest smoke tests for EffectLawSupport`

## Test Results

```
BUILD SUCCESSFUL — 6/6 tests pass
com.cajunsystems.roux.laws.LawInfrastructureTest
  ✓ succeedingEffectsWithSameValue_areEquivalent
  ✓ failingEffectsWithSameError_areEquivalent
  ✓ chainedEffectsProducingSameResult_areEquivalent
  ✓ effectsWithDifferentValues_areNotEquivalent
  ✓ successVsFailure_areNotEquivalent
  ✓ errorsWithDifferentMessages_areNotEquivalent
```

## Deviations

None. Plan executed as specified.

## Ready For

Phase 2: Functor & Monad Laws — `FunctorLawsTest` and `MonadLawsTest` can now use `EffectLawSupport.assertEquivalent()` directly.
