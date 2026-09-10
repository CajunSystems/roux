# Structured Concurrency in Roux

Roux provides **scoped structured concurrency** built on Java's `StructuredTaskScope` (JEP 453), ensuring that concurrent operations are properly managed and automatically cleaned up.

## Overview

Structured concurrency ensures that:
- **Forked effects are tracked** within a scope
- **Automatic cancellation** when scope exits (success, error, or early return)
- **No leaked threads** - all concurrent operations complete or are cancelled
- **Clear ownership** - parent scope owns all child effects

## Core API

### `Effect.scoped(body)`

Creates a scoped effect where all forked operations are automatically managed.

```java
Effect<Throwable, String> program = Effect.scoped(scope -> {
    // Fork effects within this scope
    Fiber<Throwable, String> fiber = scope.fork(longRunningTask);
    
    // All forked effects are cancelled when scope exits
    return fiber.join();
});
```

**Type:** `<E extends Throwable, A> Effect<E, A>`

### `EffectScope` Interface

The scope object passed to the scoped body provides:

#### `scope.fork(effect)`

Fork an effect within this scope. Returns an `Effect<Throwable, Fiber<E, A>>`.

```java
Effect.scoped(scope -> {
    Effect<Throwable, Fiber<Throwable, String>> fiberEffect = scope.fork(task);
    
    return fiberEffect.flatMap(fiber -> fiber.join());
});
```

#### `scope.cancelAll()`

Manually cancel all effects forked within this scope. Returns `Effect<Throwable, Unit>`.

```java
Effect.scoped(scope -> {
    scope.fork(longTask1);
    scope.fork(longTask2);
    
    // Cancel all forked tasks
    return scope.cancelAll().map(_ -> "cancelled");
});
```

#### `scope.isCancelled()`

Check if this scope has been cancelled. Returns `boolean`.

```java
Effect.scoped(scope -> {
    if (scope.isCancelled()) {
        return Effect.succeed("already cancelled");
    }
    return scope.fork(task).flatMap(Fiber::join);
});
```

### `effect.forkIn(scope)`

Convenience method to fork an effect within a specific scope.

```java
Effect.scoped(scope -> {
    return task1.forkIn(scope).flatMap(fiber1 ->
        task2.forkIn(scope).flatMap(fiber2 ->
            fiber1.join().flatMap(r1 ->
                fiber2.join().map(r2 -> combine(r1, r2))
            )
        )
    );
});
```

---

## Automatic Cancellation Guarantees

### 1. Normal Exit

When scope exits normally, all forked effects are waited for completion.

```java
Effect<Throwable, String> program = Effect.scoped(scope -> {
    scope.fork(Effect.suspend(() -> {
        Thread.sleep(100);
        System.out.println("Task completed");
        return "done";
    }));
    
    return Effect.succeed("exiting");
    // Scope waits for forked task to complete
});
```

### 2. Early Exit

When scope exits early (before forked tasks complete), they are automatically cancelled.

```java
Effect<Throwable, String> program = Effect.scoped(scope -> {
    scope.fork(Effect.suspend(() -> {
        Thread.sleep(5000); // Long running
        return "never completes";
    }));
    
    // Return immediately - forked task is cancelled
    return Effect.succeed("done");
});
```

### 3. Error Exit

When scope exits due to an error, all forked effects are cancelled.

```java
Effect<Throwable, String> program = Effect.scoped(scope -> {
    scope.fork(longRunningTask);
    
    // Throw error - forked task is cancelled
    return Effect.fail(new RuntimeException("error"));
});
```

### 4. Manual Cancellation

You can explicitly cancel all forked effects.

```java
Effect<Throwable, Integer> program = Effect.scoped(scope -> {
    AtomicInteger counter = new AtomicInteger(0);
    
    scope.fork(Effect.suspend(() -> {
        for (int i = 0; i < 100; i++) {
            Thread.sleep(10);
            counter.incrementAndGet();
        }
        return Unit.unit();
    }));
    
    // Wait a bit then cancel
    return Effect.suspend(() -> {
        Thread.sleep(50);
        return Unit.unit();
    }).flatMap(_ -> scope.cancelAll())
      .map(_ -> counter.get()); // Returns partial count
});
```

---

## Common Patterns

### Pattern 1: Parallel Task Execution

For the common case of running N independent tasks in parallel and combining their results, **reach for `Effects.par` first**. It is purpose-built for fan-out/fan-in and requires no nesting:

```java
// ✅ Idiomatic — flat, readable, no nesting
Effect<Throwable, Summary> fetchSummary(String userId) {
    return Effects.par(
        fetchUser(userId),
        fetchOrders(userId),
        fetchPreferences(userId),
        Summary::new
    );
}
```

`Effects.par` starts all three tasks concurrently, combines the results when all succeed, and propagates the first failure (fail-fast). It handles 2, 3, and 4 effects.

**If you need imperative style** — e.g. some logic between fork and join — use `Effect.effect()` with explicit fork/join. This is also flat:

```java
// ✅ Imperative style, still flat
Effect<Throwable, Summary> fetchSummary(String userId) {
    return Effect.effect(ctx -> {
        // Fork all three immediately — work starts in parallel
        var userF   = ctx.yield(fetchUser(userId).fork());
        var ordersF = ctx.yield(fetchOrders(userId).fork());
        var prefsF  = ctx.yield(fetchPreferences(userId).fork());

        // Join — results are ready (work already ran in parallel)
        var user   = ctx.yield(userF.join());
        var orders = ctx.yield(ordersF.join());
        var prefs  = ctx.yield(prefsF.join());

        return new Summary(user, orders, prefs);
    });
}
```

**Only reach for `Effect.scoped` when you need the scope handle itself** — for example, to call `scope.cancelAll()` conditionally mid-flight, or to enforce that all forked fibers are cancelled when the scope exits due to an error in a custom control flow:

```java
// ✅ Use scoped when you need explicit cancellation control
Effect<Throwable, Summary> fetchWithFallback(String userId) {
    return Effect.scoped(scope -> {
        var userF   = fetchUser(userId).forkIn(scope);
        var ordersF = fetchOrders(userId).forkIn(scope);

        return userF.flatMap(uf -> uf.join().flatMap(user -> {
            if (!user.isActive()) {
                // Cancel remaining work — only possible with a scope handle
                return scope.cancelAll().flatMap(__ ->
                    Effect.succeed(Summary.inactive(user))
                );
            }
            return ordersF.flatMap(of -> of.join().map(orders ->
                new Summary(user, orders)
            ));
        }));
    });
}
```

### Pattern 2: Background Task with Timeout

Run a task in the background and cancel if it takes too long.

```java
Effect<Throwable, String> withTimeout(Effect<Throwable, String> task, long timeoutMs) {
    return Effect.scoped(scope -> {
        return task.forkIn(scope).flatMap(fiber ->
            Effect.suspend(() -> {
                Thread.sleep(timeoutMs);
                return Unit.unit();
            }).flatMap(_ -> {
                // Cancel all (including the task)
                return scope.cancelAll().flatMap(__ ->
                    Effect.fail(new TimeoutException("Task timed out"))
                );
            })
        );
    });
}
```

### Pattern 3: Nested Scopes

Scopes can be nested for hierarchical cancellation.

```java
Effect<Throwable, Result> program = Effect.scoped(outerScope -> {
    // Outer scope manages outer tasks
    outerScope.fork(outerTask);
    
    return Effect.scoped(innerScope -> {
        // Inner scope manages inner tasks
        innerScope.fork(innerTask);
        
        // Inner scope exits first, cancelling innerTask
        // Outer scope exits later, cancelling outerTask
        return processData();
    });
});
```

### Pattern 4: Resource Management

Use scopes to ensure resources are cleaned up.

```java
Effect<Throwable, String> readFile(String path) {
    return Effect.scoped(scope -> {
        // Open resource
        FileHandle file = openFile(path);
        
        // Schedule cleanup on scope exit
        scope.fork(Effect.suspend(() -> {
            file.close();
            return Unit.unit();
        }));
        
        // Use resource
        return Effect.suspend(() -> readContent(file));
        // file.close() called automatically when scope exits
    });
}
```

### Pattern 5: Fan-out/Fan-in

For variable-length parallel work over a collection, use `Effects.parTraverse`. It handles forking, joining, and result ordering in a single call — no manual fiber management required:

```java
// ✅ Idiomatic — parTraverse handles all forking and joining
Effect<Throwable, List<Result>> processItems(List<Item> items) {
    return Effects.parTraverse(items, item -> processItem(item));
}
```

If you want to collect both successes and failures rather than fail-fast on the first error, use `parTraverseEither`:

```java
// Collect all outcomes — don't short-circuit
Effect<Throwable, List<Either<Throwable, Result>>> processAllItems(List<Item> items) {
    return Effects.parTraverseEither(items, item -> processItem(item));
}
```

Only use manual fork/join within a scope when each item's fiber needs individual inspection or conditional cancellation:

```java
// Use scoped + manual fibers only when you need per-fiber control
Effect<Throwable, List<Result>> processWithControl(List<Item> items) {
    return Effect.scoped(scope -> {
        List<Effect<Throwable, Fiber<Throwable, Result>>> fibers =
            items.stream()
                 .map(item -> processItem(item).forkIn(scope))
                 .toList();

        return Effects.sequence(
            fibers.stream()
                  .map(fe -> fe.flatMap(Fiber::join))
                  .toList()
        );
    });
}

---

## Error Handling

### Scope Cancellation on Error

When any error occurs in the scope body, all forked effects are cancelled.

```java
Effect<Throwable, String> program = Effect.scoped(scope -> {
    AtomicBoolean completed = new AtomicBoolean(false);
    
    scope.fork(Effect.suspend(() -> {
        Thread.sleep(5000);
        completed.set(true);
        return "done";
    }));
    
    // This error cancels the forked task
    return Effect.fail(new RuntimeException("boom"));
});

try {
    runtime.unsafeRun(program);
} catch (RuntimeException e) {
    // completed.get() is false - task was cancelled
}
```

### Cannot Fork in Cancelled Scope

Attempting to fork in a cancelled scope throws `IllegalStateException`.

```java
Effect<Throwable, String> program = Effect.scoped(scope -> {
    return scope.cancelAll().flatMap(_ ->
        // This will throw IllegalStateException
        Effect.succeed("test").forkIn(scope)
    );
});
```

---

## Implementation Details

### Built on Java's StructuredTaskScope

Roux's scoped concurrency is built on Java 21's `StructuredTaskScope` (JEP 453), providing:
- **Virtual thread support** - Efficient lightweight concurrency
- **Structured lifecycle** - Parent-child relationship enforcement
- **Automatic cleanup** - No leaked threads or resources

### Fiber Tracking

Each scope maintains a list of forked fibers:
- Fibers are added when forked
- All fibers are interrupted on scope exit
- Scope waits for completion or cancellation

### Cancellation Mechanism

When a scope exits:
1. **Cancel flag is set** - Prevents new forks
2. **Interrupt all fibers** - Send cancellation signal
3. **Shutdown task scope** - Clean up underlying resources
4. **Wait for completion** - Ensure all tasks finish or are cancelled

---

## Best Practices

### 1. Reach for the Highest-Level Combinator That Fits

Roux has three levels of parallel composition, from simplest to most powerful. Use the first one that fits your problem:

| Need | Use |
|------|-----|
| Fixed N tasks in parallel → combine | `Effects.par(e1, e2, e3, Combiner::new)` |
| Variable-length parallel map → list | `Effects.parTraverse(items, f)` |
| Parallel with logic between fork/join | `Effect.effect(ctx -> { ... fork ... join ... })` |
| Conditional mid-flight cancellation | `Effect.scoped(scope -> { ... scope.cancelAll() ... })` |

Manually nesting `forkIn` + `flatMap` + `join` is rarely the right answer — one of the above almost always covers it.

### 2. Avoid the Nesting Pyramid

A deeply nested flatMap/fork/join chain is a signal you should step up to a higher-level combinator:

```java
// ❌ Unnecessarily verbose
Effect.scoped(scope ->
    fetchUser(id).forkIn(scope).flatMap(uf ->
        fetchOrders(id).forkIn(scope).flatMap(of ->
            uf.join().flatMap(user ->
                of.join().map(orders -> new Summary(user, orders))
            )
        )
    )
);

// ✅ Use Effects.par instead
Effects.par(fetchUser(id), fetchOrders(id), Summary::new);
```

### 3. When to Use Scopes Directly

Reserve `Effect.scoped` for when you genuinely need the `scope` handle — conditional cancellation mid-flight, hierarchical scope nesting, or forking an unbounded number of tasks you need to individually supervise. For everything else, `Effects.par`, `Effects.parTraverse`, or `Effect.effect()` are the right tools.

### 4. Keep Scope Lifetime Short

```java
// ✅ Good - Scope only for concurrent section
Effect<Throwable, Result> process() {
    return prepare()
        .flatMap(data -> Effect.scoped(scope -> {
            // Only concurrent operations in scope
            return parallelProcess(data, scope);
        }))
        .flatMap(result -> finalize(result));
}

// ❌ Bad - Scope too broad
Effect.scoped(scope -> {
    prepare();
    parallelProcess(scope);
    finalize(); // Unnecessary scope overhead
});
```

### 5. Handle Cancellation Gracefully

```java
Effect<Throwable, String> cancellableTask = Effect.suspend(() -> {
    try {
        for (int i = 0; i < 100; i++) {
            // Check for interruption
            if (Thread.interrupted()) {
                throw new InterruptedException("Cancelled");
            }
            doWork(i);
        }
        return "completed";
    } catch (InterruptedException e) {
        // Clean up on cancellation
        cleanup();
        throw new CancelledException(e);
    }
});
```

### 6. Use Nested Scopes for Hierarchical Cancellation

```java
Effect.scoped(outerScope -> {
    // Outer scope for main workflow
    
    return Effect.scoped(innerScope -> {
        // Inner scope for sub-workflow
        // Inner tasks cancelled independently
    }).flatMap(innerResult -> {
        // Continue with outer scope
        return processOuter(innerResult);
    });
});
```

---

## Comparison with Other Approaches

### vs. Manual Fork/Join

| Aspect | Scoped | Manual Fork |
|--------|--------|-------------|
| Cleanup | Automatic | Manual |
| Error handling | Automatic cancellation | Must handle explicitly |
| Resource safety | Guaranteed | Error-prone |
| Code clarity | High | Low |

### vs. CompletableFuture

| Aspect | Scoped | CompletableFuture |
|--------|--------|-------------------|
| Cancellation | Structured | Ad-hoc |
| Error propagation | Type-safe | Exception-based |
| Composition | Effect combinators | Future combinators |
| Virtual threads | Native support | Requires executor |

---

## See Also

- [Effect API Documentation](EFFECT_API.md) - Core effect operations
- [Concurrency Patterns](CONCURRENCY_PATTERNS.md) - Advanced concurrent patterns
- [JEP 453: Structured Concurrency](https://openjdk.org/jeps/453) - Java's structured concurrency
