# Phase 13, Plan 1 — v0.3.0 Release Notes & Migration Guide

## Objective

Produce all release artefacts for v0.3.0: bump the project version, write the
CHANGELOG entry, and author `RELEASE_NOTES_0.3.0.md` with a full feature summary,
two documented breaking changes, a migration checklist, and a known-limitations section.

---

## Context

**Existing release notes format** (`RELEASE_NOTES_0.2.1.md`, `RELEASE_NOTES_0.2.0.md`):
- Emoji-decorated section headers (✨ Highlights, 🛠️ Fixes, 🧪 Testing, 📦 Installation, 🔗 Links)
- Installation section with Maven + Gradle Kotlin DSL + Gradle Groovy snippets
- Footer with `Full Changelog: vX...vY` compare link

**Files to touch:**

| File | Change |
|------|--------|
| `lib/build.gradle.kts` line 21 | `"0.2.2"` → `"0.3.0"` |
| `README.md` lines 34, 40, 45, 271, 280 | `0.2.2` → `0.3.0` (5 occurrences) |
| `PUBLISHING.md` | `0.2.2` → `0.3.0` (all occurrences) |
| `CHANGELOG.md` | Add `[0.3.0]` section before `[0.2.2]` |
| `RELEASE_NOTES_0.3.0.md` | New file — full release notes |

**New features to document (from Milestone 2 phases 7–11):**
- `EffectWithEnv<R,E,A>` — phantom-typed `Effect` wrapper; tracks capability requirements at compile time
- `HandlerEnv<R>` — typed capability environment; `of()`, `and()`, `empty()`, `fromHandler()`, `toHandler()`
- `Layer<RIn,E,ROut>` — `@FunctionalInterface` for building environments from other environments; `succeed()`, `fromEffect()`, `and()`, `andProvide()`
- `Empty` phantom type — marks effects with no capability requirements
- `With<A,B>` phantom type — capability environment union (right-nested by convention)
- `Effect.Sleep<E>` record — first-class sleep primitive (enables runtime interception)
- `TestRuntime` — overrides `performSleep()` to advance `TestClock` instead of blocking
- `TestClock` — virtual clock with `advance(Duration)`, `currentTime()`, `reset()`
- `CapabilityHandler.forType(Class<F>)` — typed builder factory; promoted as sole recommended API

**Breaking changes (from Greptile P1 findings):**
1. `Effect.Sleep<E>` is a new sealed subtype — exhaustive `switch` over `Effect` must add `Sleep` branch
2. `CapabilityHandler.Builder<F>` is now generic — raw-type references produce compiler warnings

**Deprecated:**
- `CapabilityHandler.builder()` — since v0.3.0; use `forType(Class)` instead

**Known limitations:**
- `CapabilityHandler.Builder.build()` walks only the direct interfaces of the concrete capability class. Nested sealed hierarchies where a subtype implements a registered supertype interface are silently unresolved (throws `UnsupportedOperationException`).

---

## Tasks

### Task 1 — Bump version: `build.gradle.kts`, `README.md`, `PUBLISHING.md`

**`lib/build.gradle.kts` line 21:**
```
version = "0.3.0"
```

**`README.md`** — 5 occurrences of `0.2.2` → `0.3.0` (Maven `<version>` and Gradle snippets at lines 34, 40, 45, 271, 280).

**`PUBLISHING.md`** — replace all `0.2.2` references (bundle filename, directory path, artefact names, example path, and the version comment) → `0.3.0`.

Commit: `chore(13-1): bump version to 0.3.0`

---

### Task 2 — Add `[0.3.0]` section to `CHANGELOG.md`

Insert a new section **above** the existing `## [0.2.2]` block. Use today's date: `2026-04-17`.

```markdown
## [0.3.0] - 2026-04-17

### Added
- **`EffectWithEnv<R,E,A>`** — phantom-typed wrapper over `Effect<E,A>` that statically
  tracks which capabilities an effect requires; `of()`, `pure()`, `map()`, `flatMap()`, `run()`
- **`HandlerEnv<R>`** — typed capability environment wrapper; `of()`, `and()`, `empty()`,
  `fromHandler()`, `toHandler()`
- **`Layer<RIn,E,ROut>`** — ZIO-style layer for wiring capability environments;
  `succeed()`, `fromEffect()`, `and()` (horizontal), `andProvide()` (vertical)
- **`Empty`** phantom type — marks effects that require no capabilities
- **`With<A,B>`** phantom type — capability environment union (right-nested by convention)
- **`Effect.Sleep<E>`** record — first-class sleep effect node; enables runtime interception
  and virtual-clock testing (see `TestRuntime`)
- **`TestRuntime`** — `DefaultEffectRuntime` subclass that overrides `performSleep()` to
  advance a virtual `TestClock` instead of blocking real time
- **`TestClock`** — virtual clock with `advance(Duration)`, `currentTime()`, `reset()`
- **`CapabilityHandler.forType(Class<F>)`** — type-safe builder factory; promoted as the
  sole recommended API for constructing capability handlers

### Changed
- `Effect.sleep(Duration)` now creates an `Effect.Sleep` node instead of an `Effect.Suspend`
  node — enables the new `TestRuntime` virtual-clock override
- `HandlerEnv.empty()` returns a shared static instance (was allocating a new anonymous
  class on every call; no behaviour change)
- `DefaultEffectRuntime.performSleep()` now calls `Thread.sleep(Duration)` instead of
  `Thread.sleep(long millis)` — preserves nanosecond resolution

### Deprecated
- **`CapabilityHandler.builder()`** — use `CapabilityHandler.forType(Class)` instead;
  scheduled for removal in a future major version

### Breaking Changes
- **`Effect.Sleep<E>` sealed subtype** — `Sleep` is a new permitted type in the
  `sealed interface Effect`. Any exhaustive `switch` expression over `Effect` variants
  will fail to compile without a `case Effect.Sleep<?> s -> ...` branch. Custom
  `EffectRuntime` implementations must handle `Sleep`.
- **`CapabilityHandler.Builder<F>` is now generic** — the nested class changed from
  `Builder` to `Builder<F extends Capability<?>>`. Code that explicitly references
  `CapabilityHandler.Builder` as a raw type will produce compiler warnings.
```

Also add the URL reference at the bottom of the file:
```markdown
[0.3.0]: https://github.com/CajunSystems/roux/releases/tag/v0.3.0
```

Commit: `docs(13-1): add CHANGELOG entry for v0.3.0`

---

### Task 3 — Write `RELEASE_NOTES_0.3.0.md`

Create the file at the project root (alongside the other release notes files).
Follow the established format from `RELEASE_NOTES_0.2.0.md` and `RELEASE_NOTES_0.2.1.md`.

Content outline:

```markdown
# Roux 0.3.0 — Type-Safe Capability Environments & Virtual-Clock Testing

One-line summary of the release.

## ✨ Highlights

### ✅ Compile-time capability tracking with `EffectWithEnv`
[description + code example showing HandlerEnv.of() + EffectWithEnv.run()]

### ✅ Layer system for capability wiring
[description + code example showing Layer.succeed(), and(), andProvide()]

### ✅ Phantom types: `Empty` and `With<A,B>`
[brief — explains the compile-time guarantee they provide]

### ✅ First-class `Effect.Sleep` and `TestRuntime`
[description + code example showing TestRuntime.create() + clock().currentTime()]

### ✅ `CapabilityHandler.forType()` — new recommended API
[brief — replaces deprecated builder()]

## ⚠️ Breaking Changes

### `Effect.Sleep` is a new sealed subtype (source-breaking)
[explanation + before/after switch example + migration instruction]

### `CapabilityHandler.Builder` is now generic (source-breaking)
[explanation + before/after + migration instruction]

## 🔄 Migration Guide

Checklist for upgrading from 0.2.x to 0.3.0:
- [ ] Add `case Effect.Sleep<?> s -> ...` to any exhaustive `switch` on `Effect`
- [ ] Update custom `EffectRuntime` implementations to handle `Effect.Sleep`
- [ ] Replace `CapabilityHandler.Builder` raw references with `CapabilityHandler.Builder<?>`
- [ ] Replace `CapabilityHandler.builder()` calls with `CapabilityHandler.forType(MyCapability.class)`

## ⚠️ Known Limitations

`CapabilityHandler.Builder.build()` walks only the direct interfaces of the concrete
capability class. Nested sealed hierarchies are not supported.

## 🧪 Testing

Summary of new tests added (Milestones 1 + 2 combined numbers).

## 📦 Installation

Maven + Gradle Kotlin DSL + Gradle Groovy snippets for version 0.3.0.

## 🔗 Links

GitHub, Maven Central, Documentation, Changelog links.

---
Full Changelog: v0.2.2...v0.3.0
```

Commit: `docs(13-1): add RELEASE_NOTES_0.3.0.md`

---

## Verification

After all three tasks:

```bash
./gradlew :lib:test --no-configuration-cache
```

Confirm version is `0.3.0` in the build output artifact name.

---

## Success Criteria

- [ ] `lib/build.gradle.kts` has `version = "0.3.0"`
- [ ] `README.md` shows `0.3.0` in all installation snippets (5 occurrences)
- [ ] `PUBLISHING.md` updated to `0.3.0`
- [ ] `CHANGELOG.md` has a `[0.3.0]` section with Added/Changed/Deprecated/Breaking sections
- [ ] `RELEASE_NOTES_0.3.0.md` exists with all required sections
- [ ] Both breaking changes documented with before/after examples
- [ ] Migration checklist covers all affected user scenarios
- [ ] Known limitation documented
- [ ] `./gradlew :lib:test --no-configuration-cache` passes

---

## Output

Three commits on `feat/typed-effects-with-env`. Phase 13 complete.
Milestone 3 (PR Review Fixes & v0.3.0 Release Notes) is done.
