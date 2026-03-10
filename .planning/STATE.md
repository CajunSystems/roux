# State

## Current Position
- **Milestone**: 1 — Effect Laws & Test Utilities
- **Phase**: 2 — Functor & Monad Laws
- **Plan**: Not yet planned
- **Status**: Phase 1 complete, ready to plan Phase 2

## Recent Decisions
- Law tests as JUnit tests (not documentation) — runnable proof catches regressions
- Test utilities in same artifact under test scope — no separate artifact complexity
- No new production dependencies — zero-dep production code preserved
- Java 21+ only — use modern Java freely
- Hand-crafted law inputs — specific examples are clearer proofs than generated ones
- Effect equivalence: run both effects and compare outcomes (not structural equality)
- `EffectLawSupport` is a static utility class (not abstract base) — avoids inheritance coupling
- Failure equivalence compares exception class + message (not reference) — exceptions lack meaningful equals()

## Open Issues
None

## Notes
- Uncommitted changes exist: `docs/CAPABILITIES.md` and `CapabilityHandler.java` (adds `forType()`) — review before planning Phase 3 (Capability Laws)
- Law tests will live in `com.cajunsystems.roux.laws` package
- Test utilities (Phases 4-6) live in test scope only — no production footprint
