# Roux v0.2.2

Patch release with targeted runtime fixups and documentation consistency improvements.

## Fixed

- `Resource.flatMap(...)` now correctly composes finalizers without relying on a placeholder `f.apply(null)` path.
- Outer resource release is now guaranteed when inner resource acquisition fails during `flatMap` composition.

## Documentation

- Capability docs and examples now consistently use `Unit` for side-effect-only capabilities instead of `Void`.
- Replaced `yield (R) null` patterns in examples with `Unit.unit()`.
