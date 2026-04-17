# Phase 12, Plan 1 — Greptile Code Quality Fixes

## Objective

Apply three targeted code-quality fixes identified in the Greptile review of PR #6
(CajunSystems/roux#6). No behaviour changes — each fix is a clean, non-breaking
improvement to allocation efficiency, compiler-warning hygiene, and sleep precision.

---

## Context

**Files touched:**

| File | Line | Fix |
|------|------|-----|
| `lib/src/main/java/com/cajunsystems/roux/capability/HandlerEnv.java` | 112–121 | Static constant for `empty()` |
| `lib/src/main/java/com/cajunsystems/roux/capability/CapabilityHandler.java` | 127 | `@SuppressWarnings("unused")` on `forType` |
| `lib/src/main/java/com/cajunsystems/roux/runtime/DefaultEffectRuntime.java` | 72 | `Thread.sleep(Duration)` |

**Key prior decisions (from STATE.md):**
- `HandlerEnv.empty()` uses anonymous inner class (not lambda) — Java cannot infer generic
  type for lambda assigned to `CapabilityHandler<Capability<?>>`. The static constant must
  also use an anonymous inner class for the same reason.
- `DefaultEffectRuntime.performSleep()` is `protected` and overridden in `TestRuntime`.
  `TestRuntime.performSleep()` does NOT call `super` — the fix to `DefaultEffectRuntime`
  is completely independent of `TestRuntime`.

---

## Tasks

### Task 1 — `HandlerEnv.empty()`: static constant

**File:** `lib/src/main/java/com/cajunsystems/roux/capability/HandlerEnv.java`

Replace the per-call anonymous handler allocation with a private static constant.

**Before (lines 112–121):**
```java
public static HandlerEnv<Empty> empty() {
    CapabilityHandler<Capability<?>> noOp = new CapabilityHandler<>() {
        @Override
        public <R> R handle(Capability<?> cap) {
            throw new UnsupportedOperationException(
                    "No handler registered for capability: " + cap.getClass().getName());
        }
    };
    return new HandlerEnv<>(noOp);
}
```

**After:**
```java
private static final HandlerEnv<Empty> EMPTY_INSTANCE = new HandlerEnv<>(
        new CapabilityHandler<Capability<?>>() {
            @Override
            public <R> R handle(Capability<?> cap) {
                throw new UnsupportedOperationException(
                        "No handler registered for capability: " + cap.getClass().getName());
            }
        });

public static HandlerEnv<Empty> empty() {
    return EMPTY_INSTANCE;
}
```

Note: The constant uses the explicit type `new CapabilityHandler<Capability<?>>()` (not the
diamond `<>`) for the same reason the original did — Java cannot infer this generic type.

Commit with message: `fix: cache HandlerEnv.empty() as static constant`

---

### Task 2 — `CapabilityHandler.forType()`: suppress unused-parameter warning

**File:** `lib/src/main/java/com/cajunsystems/roux/capability/CapabilityHandler.java`

Add `@SuppressWarnings("unused")` and an inline comment to the `forType` method. The
`capabilityType` parameter is intentionally unused at runtime — it exists solely to anchor
the compile-time type inference of `F`.

**Before (line 127):**
```java
static <F extends Capability<?>> Builder<F> forType(Class<F> capabilityType) {
    return new Builder<>();
}
```

**After:**
```java
@SuppressWarnings("unused") // capabilityType drives compile-time inference of F; not used at runtime
static <F extends Capability<?>> Builder<F> forType(Class<F> capabilityType) {
    return new Builder<>();
}
```

Commit with message: `fix: suppress unused-param warning on forType(); document intent`

---

### Task 3 — `DefaultEffectRuntime.performSleep()`: use `Thread.sleep(Duration)`

**File:** `lib/src/main/java/com/cajunsystems/roux/runtime/DefaultEffectRuntime.java`

Replace `Thread.sleep(duration.toMillis())` with `Thread.sleep(duration)`. The
`Thread.sleep(Duration)` overload was added in Java 19 and preserves nanosecond resolution;
`toMillis()` silently truncates anything under 1 ms.

**Before (line 72):**
```java
Thread.sleep(duration.toMillis());
```

**After:**
```java
Thread.sleep(duration);
```

`TestRuntime.performSleep()` overrides this method and never calls `super`, so it is
completely unaffected by this change.

Commit with message: `fix: use Thread.sleep(Duration) in performSleep() to avoid millis truncation`

---

## Verification

After all three tasks:

```bash
./gradlew :lib:test
```

All tests must pass with zero failures. No new test code needed — these are
non-behavioural fixes (the existing test suite covers the changed paths).

---

## Success Criteria

- [ ] `HandlerEnv.empty()` returns the same `EMPTY_INSTANCE` object on every call
- [ ] `forType()` has `@SuppressWarnings("unused")` with explanatory comment
- [ ] `performSleep()` calls `Thread.sleep(Duration)` (no `.toMillis()`)
- [ ] `./gradlew :lib:test` passes — zero failures
- [ ] Three commits, one per fix

---

## Output

Three commits on `feat/typed-effects-with-env`. Phase 12 complete.
Next: Phase 13 — `RELEASE_NOTES_0.3.0.md` + migration guide.
