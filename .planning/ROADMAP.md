# Roadmap — Roux Effect Laws & Test Utilities

## Milestone 1: Effect Laws & Test Utilities

**Goal:** Prove Roux's algebra is correct via runnable law tests, and provide practical test utilities that reduce boilerplate for effect-based tests.

**Success:** All mathematical laws verified as passing JUnit tests; developers can write fluent effect assertions, use a controllable TestRuntime, and build capability test doubles with minimal ceremony.

---

### Phase 1: Law Test Infrastructure

**Goal:** Establish the foundation for law testing — define how two effects are compared for equivalence (by running them and comparing results), create a base law test structure, and document the approach.

**Deliverables:**
- `EffectLaw` base class or interface for structuring law tests
- Effect equivalence helper: run both effects and assert equal outcomes
- Law test naming conventions and structure
- Decision: where law tests live (separate package, e.g. `com.cajunsystems.roux.laws`)

**Research flags:** None — approach is straightforward JUnit

---

### Phase 2: Functor & Monad Laws

**Goal:** Implement and verify the 5 core algebraic laws that define correct `Effect` behavior as a functor and monad.

**Laws:**
- **Functor identity**: `effect.map(x -> x)` ≡ `effect`
- **Functor composition**: `effect.map(f.andThen(g))` ≡ `effect.map(f).map(g)`
- **Monad left identity**: `Effect.succeed(a).flatMap(f)` ≡ `f.apply(a)`
- **Monad right identity**: `effect.flatMap(Effect::succeed)` ≡ `effect`
- **Monad associativity**: `effect.flatMap(f).flatMap(g)` ≡ `effect.flatMap(a -> f.apply(a).flatMap(g))`

**Deliverables:**
- `FunctorLawsTest.java`
- `MonadLawsTest.java`
- Tests pass with both success and failure effects as inputs

---

### Phase 3: Error Channel & Capability Laws

**Goal:** Verify that error handling combinators and capability handler composition satisfy their algebraic laws.

**Laws:**
- **catchAll identity**: `effect.catchAll(Effect::fail)` ≡ `effect`
- **mapError identity**: `effect.mapError(e -> e)` ≡ `effect`
- **mapError composition**: `effect.mapError(f).mapError(g)` ≡ `effect.mapError(f.andThen(g))`
- **attempt round-trip**: `effect.attempt().flatMap(either -> either.fold(Effect::fail, Effect::succeed))` ≡ `effect`
- **CapabilityHandler orElse identity**: handler composed with no-op fallback ≡ handler
- **CapabilityHandler compose associativity**: `(h1.compose(h2)).compose(h3)` ≡ `h1.compose(h2.compose(h3))`

**Deliverables:**
- `ErrorChannelLawsTest.java`
- `CapabilityHandlerLawsTest.java`

---

### Phase 4: Fluent Effect Assertions

**Goal:** Provide a clean, readable assertion API for effect-based tests so developers don't have to manually call `unsafeRun` and catch exceptions in every test.

**API shape:**
```java
EffectAssertions.assertThat(effect)
    .succeedsWith(42)
    .succeedsWith(v -> v > 0)   // predicate form
    .succeeds()
    .failsWith(IOException.class)
    .failsWith(e -> e.getMessage().contains("timeout"))
    .fails()
```

**Deliverables:**
- `EffectAssertions` class in test scope
- `EffectAssert` fluent chain class
- Supports both `Effect<E,A>` with runtime + optional handler

---

### Phase 5: TestRuntime ✓ Complete

**Goal:** A test-specific `EffectRuntime` that gives deterministic, controllable execution — manual clock advancement for testing time-dependent effects, and fiber state inspection.

**Capabilities:**
- `TestClock` — manual `advance(Duration)`, query current virtual time
- `TestRuntime.create()` — deterministic execution (no background threads unless explicitly forked)
- Fiber state inspection: is it running, joined, interrupted?
- Override `sleep(Duration)` to use TestClock rather than real wall time

**Deliverables:**
- `TestRuntime` class in test scope
- `TestClock` class
- Tests demonstrating retry-with-delay and timeout testing without real sleeps

---

### Phase 6: Capability Test Doubles ✓ Complete

**Goal:** Reduce boilerplate for building `CapabilityHandler` stubs and spies in tests. Let developers set up handlers that track calls, return canned responses, and assert interaction patterns.

**API shape:**
```java
TestCapabilityHandler handler = TestCapabilityHandler.builder()
    .stub(FetchUser.class, req -> new User(req.userId(), "Alice"))
    .stub(ChargeCard.class, req -> PaymentResult.success())
    .build();

// After running effects:
handler.verify(FetchUser.class).calledOnce();
handler.verify(ChargeCard.class).calledWith(req -> req.amount() == 99.0);
handler.verify(FetchUser.class).neverCalled();
```

**Deliverables:**
- `TestCapabilityHandler` with stub builder
- Call recording and verification API
- Integration example showing end-to-end test with stubs + assertions

---

## Out of Scope (v1)
- Property-based testing — no jqwik/QuickCheck, hand-crafted inputs only
- Separate `roux-test` artifact — all test utilities in same library, test scope
- Concurrency law tests — Fork/Race/Scoped laws deferred
- Backwards compat shims — Java 21+ only

---

## Milestone 2: Type-Safe Layer System

**Goal:** Extend the capability system with a ZIO-style layer mechanism that gives compile-time verification that every capability an effect uses has a handler — no runtime `UnsupportedOperationException` from a forgotten handler; the program won't compile.

**Success:** Developers declare `EffectWithEnv<R, E, A>` encoding which capabilities are needed, compose `HandlerEnv<R>` environments the compiler checks against, and wire dependencies through `Layer<RIn, E, ROut>` recipes.

---

### Phase 7: Phantom Types + HandlerEnv ✓ Complete

**Goal:** Establish the type-level foundation: `Empty` and `With<A, B>` phantom interfaces, plus `HandlerEnv<R>` — a typed wrapper around `CapabilityHandler` that tracks which capabilities it covers at compile time.

**Deliverables:**
- `Empty` — phantom marker interface (base case, no capabilities)
- `With<A, B>` — phantom marker interface (both A and B present)
- `HandlerEnv<R>` — `.of()` (F-bounded), `.and()`, `.fromHandler()`, `.empty()`, `.toHandler()`

**Research flags:** Low

---

### Phase 8: EffectWithEnv ✓ Complete

**Goal:** `EffectWithEnv<R, E, A>` — a thin wrapper around `Effect<E, A>` that statically declares which capability environment `R` the effect needs.

**Deliverables:**
- `EffectWithEnv<R, E, A>` — `.of()`, `.pure()`, `.map()`, `.flatMap()`, `.run(HandlerEnv<R>, EffectRuntime)`

**Research flags:** Low

---

### Phase 9: Layer Core ✓ Complete

**Goal:** `Layer<RIn, E, ROut>` — recipe that builds a `HandlerEnv<ROut>` from a `HandlerEnv<RIn>`, possibly performing effects during construction.

**Deliverables:**
- `Layer<RIn, E, ROut>` — `.succeed()`, `.fromEffect()`, `.build()`

**Research flags:** Medium

---

### Phase 10: Layer Composition ✓ Complete

**Goal:** Horizontal (`and`) and vertical (`andProvide`) composition operators.

**Deliverables:**
- `Layer.and()` — same input, merged output `With<ROut, S>`
- `Layer.andProvide()` — this output feeds next input; both outputs retained

**Research flags:** High

---

### Phase 11: F-Bounded Builder + Integration Tests ✓ Complete

**Goal:** Upgrade `CapabilityHandler.Builder.on()` to F-bounded return type and write end-to-end integration tests for the full Layer → Env → EffectWithEnv workflow.

**Deliverables:**
- `CapabilityHandler.Builder.on()` upgraded to `<R, C extends F & Capability<R>>`
- `LayerIntegrationTest` — full workflow test
- Regression: all existing tests still pass

**Research flags:** Medium

---
