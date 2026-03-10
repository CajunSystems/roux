# State

## Current Position
- **Milestone**: 2 — Type-Safe Layer System
- **Phase**: 10 — Layer Composition ✓ Complete
- **Plan**: 10-1 complete
- **Status**: Phase 10 complete — Layer composition operators added and verified; ready for Phase 11

## Recent Decisions
- Law tests as JUnit tests (not documentation) — runnable proof catches regressions
- Test utilities in same artifact under test scope — no separate artifact complexity
- No new production dependencies — zero-dep production code preserved
- Java 21+ only — use modern Java freely
- Hand-crafted law inputs — specific examples are clearer proofs than generated ones
- Effect equivalence: run both effects and compare outcomes (not structural equality)
- `EffectLawSupport` is a static utility class (not abstract base) — avoids inheritance coupling
- Failure equivalence compares exception class + message (not reference) — exceptions lack meaningful equals()
- Milestone 2: `EffectWithEnv<R,E,A>` is a thin wrapper — `Effect<E,A>` production API unchanged
- Milestone 2: Phantom types `Empty`/`With<A,B>` are never instantiated — compile-time only
- Milestone 2: `HandlerEnv.and()` returns `HandlerEnv<With<R, S>>` — right-nested by convention
- `HandlerEnv.of()` phantom type is C (the capability family), not R (the return type)
- `EffectWithEnv.of()` R is phantom — caller declares it explicitly; Java cannot infer phantom types
- `EffectWithEnv.flatMap()` accesses private `.effect` field of other instance — legal in Java (per-class not per-instance)
- `Layer<RIn, E, ROut>` is a @FunctionalInterface — build() is the SAM
- `Layer.succeed()` returns `Layer<Empty, RuntimeException, C>` — leaf layer, error type is RuntimeException
- `Layer.fromEffect()` lambda receives HandlerEnv<RIn> and returns Effect<E, ThrowingFunction<C, R>>
- `Layer.and()` and `Layer.andProvide()` widen both error types to `Throwable` (no Java union types for checked exceptions)
- `DefaultEffectRuntime.create()` is the correct factory (no-arg constructor does not exist); tests must call `runtime.close()` in `@AfterEach`

## Open Issues
None

## Notes
- Capability handler laws test `handle()` dispatch directly (no EffectRuntime needed)
- Law tests package: `com.cajunsystems.roux.laws` — established and populated
- Test utilities (Phases 4-6) live in test scope only — no production footprint
- Sealed interfaces cannot be local classes in Java — must be at class/top level (learned in Phase 4)
- Sleep is now a first-class Effect primitive (not a Suspend thunk) — enables runtime interception
- TestRuntime.performSleep override uses virtual clock; Sleep record has one type param E (not two)
- TestCapabilityHandler implements CapabilityHandler<Capability<?>> directly — no .widen() needed at call sites
- CapabilityVerifier uses JUnit assertions internally (test scope only — no production impact)
- Phase 10 (Layer composition) has HIGH research flag — andProvide type threading is most complex generics challenge
- Phase 11 (F-bounded Builder) has MEDIUM flag — existing call sites may need updates after on() signature change
- HandlerEnv, Empty, With, Layer live in com.cajunsystems.roux.capability package
- HandlerEnv.toHandler() returns CapabilityHandler<Capability<?>> for use with unsafeRunWithHandler
- HandlerEnv.empty() uses anonymous inner class (not lambda) — Java cannot infer generic type for lambda assigned to CapabilityHandler<Capability<?>>
- EffectWithEnv lives in com.cajunsystems.roux alongside Effect
- Layer.fromEffect map: effectFn.apply(env).map(h -> HandlerEnv.of(type, h)) — may need local variable for type inference
