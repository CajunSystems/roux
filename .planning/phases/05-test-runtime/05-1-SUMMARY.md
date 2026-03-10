# Summary: 05-1 — TestRuntime

## Status: Complete

## What Was Done

Added a `Sleep` primitive to the `Effect` sealed interface so that sleep behaviour can be intercepted by the runtime rather than executing as a raw `Thread.sleep()` inside a `Suspend` thunk. Added a `performSleep(Duration)` hook to `DefaultEffectRuntime` (both the trampoline path and the legacy recursive path), allowing subclasses to override sleep behaviour. Created `TestClock`, a virtual clock backed by an `AtomicLong` that advances only when told to. Created `TestRuntime`, a subclass of `DefaultEffectRuntime` that overrides `performSleep` to advance the `TestClock` instead of blocking real time. Created `TestRuntimeTest` with 6 tests demonstrating clock initialisation, single/accumulated sleeps, no real-time blocking, virtual-time retry-with-delay, and shared clock scenarios.

One compile fix was required: the `Sleep` record has a single type parameter `E`, so pattern-match arms in the runtime needed `Effect.Sleep<?>` rather than `Effect.Sleep<?, ?>`.

## Commits

- e53b981 — feat(05-1): add Sleep primitive to Effect sealed interface
- bd565af — feat(05-1): add performSleep hook and Sleep handling to DefaultEffectRuntime
- 12cc312 — feat(05-1): add TestClock virtual clock for testing
- 4211273 — feat(05-1): add TestRuntime with virtual clock override
- 30e3f5a — test(05-1): add TestRuntimeTest with 6 demo tests

## Test Results

TestRuntimeTest: 6 tests, 0 failures, 0 errors (total suite: 270 tests, 0 failures, 0 errors)

```
testsuite name="com.cajunsystems.roux.testing.TestRuntimeTest" tests="6" skipped="0" failures="0" errors="0" time="0.027"
  retryWithDelay_usesVirtualTimeAndRunsFast()  — PASS (0.023s)
  createWithClock_sharesClockState()           — PASS (0.000s)
  sleep_doesNotBlockRealTime()                 — PASS (0.001s)
  clock_advancesWhenSleepEffectRuns()          — PASS (0.001s)
  clock_accumulatesMultipleSleeps()            — PASS (0.000s)
  clock_startsAtZero()                         — PASS (0.000s)
```

## Ready For

Phase 6: Capability Test Doubles
