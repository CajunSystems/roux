# State

## Current Position
- **Milestone**: 1 — Effect Laws & Test Utilities
- **Phase**: 5 — TestRuntime
- **Plan**: Not yet planned
- **Status**: Phase 4 complete — EffectAssertions API built and verified; ready to plan Phase 5

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
- Capability handler laws test `handle()` dispatch directly (no EffectRuntime needed)
- Law tests package: `com.cajunsystems.roux.laws` — established and populated
- Test utilities (Phases 4-6) live in test scope only — no production footprint
- Sealed interfaces cannot be local classes in Java — must be at class/top level (learned in Phase 4)
- Uncommitted changes to `docs/CAPABILITIES.md` and `CapabilityHandler.java` still present — not blocking
