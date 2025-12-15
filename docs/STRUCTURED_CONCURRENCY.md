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

Run multiple tasks in parallel and wait for all results.

```java
Effect<Throwable, Summary> fetchSummary(String userId) {
    return Effect.scoped(scope -> {
        // Fork all tasks in parallel
        Effect<Throwable, Fiber<Throwable, User>> userFiber = 
            fetchUser(userId).forkIn(scope);
        Effect<Throwable, Fiber<Throwable, List<Order>>> ordersFiber = 
            fetchOrders(userId).forkIn(scope);
        Effect<Throwable, Fiber<Throwable, Preferences>> prefsFiber = 
            fetchPreferences(userId).forkIn(scope);
        
        // Wait for all results
        return userFiber.flatMap(uf ->
            ordersFiber.flatMap(of ->
                prefsFiber.flatMap(pf ->
                    uf.join().flatMap(user ->
                        of.join().flatMap(orders ->
                            pf.join().map(prefs ->
                                new Summary(user, orders, prefs)
                            )
                        )
                    )
                )
            )
        );
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

Fork multiple tasks and collect results.

```java
Effect<Throwable, List<Result>> processItems(List<Item> items) {
    return Effect.scoped(scope -> {
        // Fork a task for each item
        List<Effect<Throwable, Fiber<Throwable, Result>>> fibers = 
            items.stream()
                .map(item -> processItem(item).forkIn(scope))
                .toList();
        
        // Collect all results
        Effect<Throwable, List<Result>> results = Effect.succeed(new ArrayList<>());
        for (Effect<Throwable, Fiber<Throwable, Result>> fiberEffect : fibers) {
            results = results.flatMap(list ->
                fiberEffect.flatMap(fiber ->
                    fiber.join().map(result -> {
                        list.add(result);
                        return list;
                    })
                )
            );
        }
        return results;
    });
}
```

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

### 1. Always Use Scopes for Concurrent Operations

```java
// ✅ Good - Scoped concurrency
Effect.scoped(scope -> {
    task1.forkIn(scope);
    task2.forkIn(scope);
    return result;
});

// ❌ Bad - Unscoped fork (no automatic cleanup)
task1.fork().flatMap(fiber -> /* might leak */);
```

### 2. Keep Scope Lifetime Short

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

### 3. Handle Cancellation Gracefully

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

### 4. Use Nested Scopes for Hierarchical Cancellation

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
