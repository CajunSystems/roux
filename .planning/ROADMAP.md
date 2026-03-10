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

### Phase 5: TestRuntime

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

### Phase 6: Capability Test Doubles

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
