# Capability Recipes

This guide shows powerful patterns you can build on top of Roux's capability system. Each recipe demonstrates a different use case for algebraic effects.

## Capability-Effect Bridge

Roux provides a bridge between capabilities and effects, allowing you to use all Effect operators with capabilities:

```java
// Convert capability to effect
Effect<Throwable, User> effect = new GetUser("123")
    .toEffect()
    .map(json -> parseJson(json, User.class))
    .catchAll(e -> Effect.succeed(User.GUEST));

// Run with handler
User user = runtime.unsafeRunWithHandler(effect, handler);

// Parallel execution with zipPar
Effect<Throwable, Dashboard> dashboard = new GetUser("123")
    .toEffect()
    .zipPar(new GetOrders("123").toEffect(), Dashboard::new);

// Or use static helpers for 3+ effects
import static com.cajunsystems.roux.Effects.*;

Effect<Throwable, Summary> summary = par(
    new GetUser("123").toEffect(),
    new GetOrders("123").toEffect(),
    new GetPreferences("123").toEffect(),
    Summary::new
);
```

**Key Benefits:**
- All Effect operators work with capabilities (map, flatMap, retry, timeout, etc.)
- Handler is implicit - provided at runtime
- Clean, composable API
- Type-safe

---

## Table of Contents

1. [Resource Management](#1-resource-management)
2. [Retry & Backoff](#2-retry--backoff)
3. [Caching](#3-caching)
4. [Distributed Tracing](#4-distributed-tracing)
5. [Circuit Breaker](#5-circuit-breaker)
6. [Rate Limiting](#6-rate-limiting)
7. [Metrics Collection](#7-metrics-collection)
8. [Feature Flags](#8-feature-flags)
9. [Saga Pattern](#9-saga-pattern)
10. [Dependency Injection](#10-dependency-injection)
11. [Parallel Execution](#11-parallel-execution)
12. [Time Travel & Replay](#12-time-travel--replay)

---

## 1. Resource Management

Automatically acquire and release resources with guaranteed cleanup.

### Capability Definition

```java
public sealed interface ResourceCapability<R> extends Capability<R> {
    record Acquire<T>(String resourceId, Supplier<T> acquire) 
        implements ResourceCapability<T> {}
    
    record Release<T>(T resource, Consumer<T> release) 
        implements ResourceCapability<Void> {}
    
    record Bracket<T, R>(
        Capability<T> acquire,
        Function<T, Capability<R>> use,
        Function<T, Capability<Void>> release
    ) implements ResourceCapability<R> {}
}
```

### Handler Implementation

```java
public class ResourceHandler implements CapabilityHandler<ResourceCapability<?>> {
    @Override
    @SuppressWarnings("unchecked")
    public <R> R handle(ResourceCapability<?> capability) throws Exception {
        return switch (capability) {
            case ResourceCapability.Acquire<?> acq -> 
                (R) acq.acquire().get();
            
            case ResourceCapability.Release<?> rel -> {
                rel.release().accept(rel.resource());
                yield (R) null;
            }
            
            case ResourceCapability.Bracket<?, ?> bracket -> {
                Object resource = null;
                try {
                    resource = handle(bracket.acquire());
                    return (R) handle(bracket.use().apply(resource));
                } finally {
                    if (resource != null) {
                        handle(bracket.release().apply(resource));
                    }
                }
            }
        };
    }
}
```

### Usage

```java
Effect<Throwable, String> readFile = Effect.generate(ctx -> {
    return ctx.perform(new ResourceCapability.Bracket<>(
        new ResourceCapability.Acquire<>("file", () -> openFile("data.txt")),
        file -> new FileCapability.Read(file),
        file -> new FileCapability.Close(file)
    ));
}, handler);
```

---

## 2. Retry & Backoff

Automatically retry failed operations with configurable backoff strategies.

### Capability Definition

```java
public sealed interface RetryCapability<R> extends Capability<R> {
    record WithRetry<T>(
        Capability<T> operation,
        int maxAttempts,
        Duration initialBackoff,
        BackoffStrategy strategy
    ) implements RetryCapability<T> {}
    
    enum BackoffStrategy { FIXED, EXPONENTIAL, FIBONACCI }
}
```

### Handler Implementation

```java
public class RetryHandler implements CapabilityHandler<RetryCapability<?>> {
    private final CapabilityHandler<Capability<?>> delegate;
    
    @Override
    @SuppressWarnings("unchecked")
    public <R> R handle(RetryCapability<?> capability) throws Exception {
        return switch (capability) {
            case RetryCapability.WithRetry<?> retry -> {
                Exception lastError = null;
                Duration backoff = retry.initialBackoff();
                
                for (int attempt = 0; attempt < retry.maxAttempts(); attempt++) {
                    try {
                        yield (R) delegate.handle(retry.operation());
                    } catch (Exception e) {
                        lastError = e;
                        if (attempt < retry.maxAttempts() - 1) {
                            Thread.sleep(backoff.toMillis());
                            backoff = calculateNextBackoff(backoff, retry.strategy());
                        }
                    }
                }
                throw lastError;
            }
        };
    }
    
    private Duration calculateNextBackoff(Duration current, BackoffStrategy strategy) {
        return switch (strategy) {
            case FIXED -> current;
            case EXPONENTIAL -> current.multipliedBy(2);
            case FIBONACCI -> current.multipliedBy(2); // Simplified
        };
    }
}
```

### Usage

```java
Effect<Throwable, String> resilientFetch = Effect.generate(ctx -> {
    return ctx.perform(new RetryCapability.WithRetry<>(
        new HttpCapability.Get("https://api.example.com/data"),
        maxAttempts: 3,
        initialBackoff: Duration.ofSeconds(1),
        strategy: BackoffStrategy.EXPONENTIAL
    ));
}, handler);
```

---

## 3. Caching

Transparent caching with TTL support.

### Capability Definition

```java
public sealed interface CacheCapability<R> extends Capability<R> {
    record Cached<T>(
        String key,
        Capability<T> computation,
        Duration ttl
    ) implements CacheCapability<T> {}
    
    record Invalidate(String key) implements CacheCapability<Void> {}
    
    record Clear() implements CacheCapability<Void> {}
}
```

### Handler Implementation

```java
public class CacheHandler implements CapabilityHandler<CacheCapability<?>> {
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final CapabilityHandler<Capability<?>> delegate;
    
    record CacheEntry(Object value, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public <R> R handle(CacheCapability<?> capability) throws Exception {
        return switch (capability) {
            case CacheCapability.Cached<?> cached -> {
                CacheEntry entry = cache.get(cached.key());
                
                if (entry != null && !entry.isExpired()) {
                    yield (R) entry.value();
                }
                
                R result = (R) delegate.handle(cached.computation());
                cache.put(cached.key(), new CacheEntry(
                    result,
                    Instant.now().plus(cached.ttl())
                ));
                yield result;
            }
            
            case CacheCapability.Invalidate inv -> {
                cache.remove(inv.key());
                yield (R) null;
            }
            
            case CacheCapability.Clear clear -> {
                cache.clear();
                yield (R) null;
            }
        };
    }
}
```

### Usage

```java
Effect<Throwable, User> getUser = Effect.generate(ctx -> {
    return ctx.perform(new CacheCapability.Cached<>(
        "user:123",
        new DbCapability.Query("SELECT * FROM users WHERE id = ?", List.of(123)),
        Duration.ofMinutes(5)
    ));
}, handler);
```

---

## 4. Distributed Tracing

Automatic span creation and attribute tracking for observability.

### Capability Definition

```java
public sealed interface TracingCapability<R> extends Capability<R> {
    record StartSpan(String name, Map<String, String> attributes) 
        implements TracingCapability<SpanId> {}
    
    record EndSpan(SpanId id, SpanStatus status) 
        implements TracingCapability<Void> {}
    
    record AddAttribute(SpanId id, String key, String value) 
        implements TracingCapability<Void> {}
    
    record AddEvent(SpanId id, String name, Map<String, String> attributes) 
        implements TracingCapability<Void> {}
    
    record Traced<T>(
        String spanName,
        Capability<T> operation
    ) implements TracingCapability<T> {}
    
    enum SpanStatus { OK, ERROR }
}
```

### Handler Implementation

```java
public class TracingHandler implements CapabilityHandler<TracingCapability<?>> {
    private final Tracer tracer; // OpenTelemetry or similar
    private final CapabilityHandler<Capability<?>> delegate;
    private final Map<SpanId, Span> activeSpans = new ConcurrentHashMap<>();
    
    @Override
    @SuppressWarnings("unchecked")
    public <R> R handle(TracingCapability<?> capability) throws Exception {
        return switch (capability) {
            case TracingCapability.StartSpan start -> {
                Span span = tracer.spanBuilder(start.name()).startSpan();
                start.attributes().forEach(span::setAttribute);
                SpanId id = new SpanId(UUID.randomUUID());
                activeSpans.put(id, span);
                yield (R) id;
            }
            
            case TracingCapability.EndSpan end -> {
                Span span = activeSpans.remove(end.id());
                if (span != null) {
                    span.setStatus(end.status() == SpanStatus.OK 
                        ? StatusCode.OK 
                        : StatusCode.ERROR);
                    span.end();
                }
                yield (R) null;
            }
            
            case TracingCapability.AddAttribute attr -> {
                Span span = activeSpans.get(attr.id());
                if (span != null) {
                    span.setAttribute(attr.key(), attr.value());
                }
                yield (R) null;
            }
            
            case TracingCapability.AddEvent event -> {
                Span span = activeSpans.get(event.id());
                if (span != null) {
                    span.addEvent(event.name(), 
                        Attributes.of(event.attributes()));
                }
                yield (R) null;
            }
            
            case TracingCapability.Traced<?> traced -> {
                SpanId spanId = handle(new TracingCapability.StartSpan(
                    traced.spanName(), Map.of()
                ));
                try {
                    R result = (R) delegate.handle(traced.operation());
                    handle(new TracingCapability.EndSpan(spanId, SpanStatus.OK));
                    yield result;
                } catch (Exception e) {
                    handle(new TracingCapability.EndSpan(spanId, SpanStatus.ERROR));
                    throw e;
                }
            }
        };
    }
}
```

### Usage

```java
Effect<Throwable, Result> traced = Effect.generate(ctx -> {
    return ctx.perform(new TracingCapability.Traced<>(
        "fetchUserData",
        new HttpCapability.Get("https://api.example.com/user/123")
    ));
}, handler);
```

---

## 5. Circuit Breaker

Prevent cascading failures with automatic circuit breaking.

### Capability Definition

```java
public sealed interface CircuitBreakerCapability<R> extends Capability<R> {
    record Protected<T>(
        String circuitName,
        Capability<T> operation,
        CircuitBreakerConfig config
    ) implements CircuitBreakerCapability<T> {}
    
    record GetState(String circuitName) 
        implements CircuitBreakerCapability<CircuitState> {}
    
    record Reset(String circuitName) 
        implements CircuitBreakerCapability<Void> {}
    
    enum CircuitState { CLOSED, OPEN, HALF_OPEN }
    
    record CircuitBreakerConfig(
        int failureThreshold,
        Duration timeout,
        int successThreshold
    ) {}
}
```

### Handler Implementation

```java
public class CircuitBreakerHandler 
        implements CapabilityHandler<CircuitBreakerCapability<?>> {
    private final Map<String, CircuitBreaker> circuits = new ConcurrentHashMap<>();
    private final CapabilityHandler<Capability<?>> delegate;
    
    record CircuitBreaker(
        AtomicInteger failures,
        AtomicInteger successes,
        AtomicReference<CircuitState> state,
        AtomicReference<Instant> openedAt,
        CircuitBreakerConfig config
    ) {}
    
    @Override
    @SuppressWarnings("unchecked")
    public <R> R handle(CircuitBreakerCapability<?> capability) throws Exception {
        return switch (capability) {
            case CircuitBreakerCapability.Protected<?> prot -> {
                CircuitBreaker circuit = circuits.computeIfAbsent(
                    prot.circuitName(),
                    k -> createCircuitBreaker(prot.config())
                );
                
                if (circuit.state().get() == CircuitState.OPEN) {
                    if (shouldAttemptReset(circuit)) {
                        circuit.state().set(CircuitState.HALF_OPEN);
                    } else {
                        throw new CircuitBreakerOpenException(prot.circuitName());
                    }
                }
                
                try {
                    R result = (R) delegate.handle(prot.operation());
                    onSuccess(circuit);
                    yield result;
                } catch (Exception e) {
                    onFailure(circuit);
                    throw e;
                }
            }
            
            case CircuitBreakerCapability.GetState get -> 
                (R) circuits.get(get.circuitName()).state().get();
            
            case CircuitBreakerCapability.Reset reset -> {
                CircuitBreaker circuit = circuits.get(reset.circuitName());
                if (circuit != null) {
                    circuit.state().set(CircuitState.CLOSED);
                    circuit.failures().set(0);
                    circuit.successes().set(0);
                }
                yield (R) null;
            }
        };
    }
    
    private void onSuccess(CircuitBreaker circuit) {
        circuit.failures().set(0);
        if (circuit.state().get() == CircuitState.HALF_OPEN) {
            if (circuit.successes().incrementAndGet() >= 
                    circuit.config().successThreshold()) {
                circuit.state().set(CircuitState.CLOSED);
                circuit.successes().set(0);
            }
        }
    }
    
    private void onFailure(CircuitBreaker circuit) {
        circuit.successes().set(0);
        if (circuit.failures().incrementAndGet() >= 
                circuit.config().failureThreshold()) {
            circuit.state().set(CircuitState.OPEN);
            circuit.openedAt().set(Instant.now());
        }
    }
    
    private boolean shouldAttemptReset(CircuitBreaker circuit) {
        return Instant.now().isAfter(
            circuit.openedAt().get().plus(circuit.config().timeout())
        );
    }
}
```

### Usage

```java
Effect<Throwable, String> protected = Effect.generate(ctx -> {
    return ctx.perform(new CircuitBreakerCapability.Protected<>(
        "external-api",
        new HttpCapability.Get("https://flaky-api.com/data"),
        new CircuitBreakerConfig(
            failureThreshold: 5,
            timeout: Duration.ofSeconds(60),
            successThreshold: 2
        )
    ));
}, handler);
```

---

## 6. Rate Limiting

Control request rates with sliding window or token bucket algorithms.

### Capability Definition

```java
public sealed interface RateLimitCapability<R> extends Capability<R> {
    record RateLimited<T>(
        String limitKey,
        int maxRequests,
        Duration window,
        Capability<T> operation
    ) implements RateLimitCapability<T> {}
    
    record GetRemaining(String limitKey) 
        implements RateLimitCapability<Integer> {}
}
```

### Handler Implementation

```java
public class RateLimitHandler implements CapabilityHandler<RateLimitCapability<?>> {
    private final Map<String, RateLimiter> limiters = new ConcurrentHashMap<>();
    private final CapabilityHandler<Capability<?>> delegate;
    
    record RateLimiter(
        Queue<Instant> requests,
        int maxRequests,
        Duration window
    ) {
        synchronized void cleanup() {
            Instant cutoff = Instant.now().minus(window);
            requests.removeIf(time -> time.isBefore(cutoff));
        }
        
        synchronized boolean tryAcquire() {
            cleanup();
            if (requests.size() < maxRequests) {
                requests.add(Instant.now());
                return true;
            }
            return false;
        }
        
        synchronized int remaining() {
            cleanup();
            return maxRequests - requests.size();
        }
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public <R> R handle(RateLimitCapability<?> capability) throws Exception {
        return switch (capability) {
            case RateLimitCapability.RateLimited<?> limited -> {
                RateLimiter limiter = limiters.computeIfAbsent(
                    limited.limitKey(),
                    k -> new RateLimiter(
                        new ConcurrentLinkedQueue<>(),
                        limited.maxRequests(),
                        limited.window()
                    )
                );
                
                if (!limiter.tryAcquire()) {
                    throw new RateLimitExceededException(limited.limitKey());
                }
                
                yield (R) delegate.handle(limited.operation());
            }
            
            case RateLimitCapability.GetRemaining get -> 
                (R) Integer.valueOf(limiters.get(get.limitKey()).remaining());
        };
    }
}
```

### Usage

```java
Effect<Throwable, String> limited = Effect.generate(ctx -> {
    return ctx.perform(new RateLimitCapability.RateLimited<>(
        "api-calls",
        maxRequests: 100,
        window: Duration.ofMinutes(1),
        operation: new HttpCapability.Post("url", body)
    ));
}, handler);
```

---

## 7. Metrics Collection

Automatic metrics collection for observability.

### Capability Definition

```java
public sealed interface MetricsCapability<R> extends Capability<R> {
    record IncrementCounter(String name, Map<String, String> tags) 
        implements MetricsCapability<Void> {}
    
    record RecordGauge(String name, double value, Map<String, String> tags) 
        implements MetricsCapability<Void> {}
    
    record RecordHistogram(String name, double value, Map<String, String> tags) 
        implements MetricsCapability<Void> {}
    
    record Timed<T>(
        String name,
        Map<String, String> tags,
        Capability<T> operation
    ) implements MetricsCapability<T> {}
}
```

### Handler Implementation

```java
public class MetricsHandler implements CapabilityHandler<MetricsCapability<?>> {
    private final MeterRegistry registry; // Micrometer or similar
    private final CapabilityHandler<Capability<?>> delegate;
    
    @Override
    @SuppressWarnings("unchecked")
    public <R> R handle(MetricsCapability<?> capability) throws Exception {
        return switch (capability) {
            case MetricsCapability.IncrementCounter counter -> {
                Counter.builder(counter.name())
                    .tags(toTags(counter.tags()))
                    .register(registry)
                    .increment();
                yield (R) null;
            }
            
            case MetricsCapability.RecordGauge gauge -> {
                Gauge.builder(gauge.name(), () -> gauge.value())
                    .tags(toTags(gauge.tags()))
                    .register(registry);
                yield (R) null;
            }
            
            case MetricsCapability.RecordHistogram histogram -> {
                DistributionSummary.builder(histogram.name())
                    .tags(toTags(histogram.tags()))
                    .register(registry)
                    .record(histogram.value());
                yield (R) null;
            }
            
            case MetricsCapability.Timed<?> timed -> {
                Timer.Sample sample = Timer.start(registry);
                try {
                    R result = (R) delegate.handle(timed.operation());
                    sample.stop(Timer.builder(timed.name())
                        .tags(toTags(timed.tags()))
                        .register(registry));
                    yield result;
                } catch (Exception e) {
                    sample.stop(Timer.builder(timed.name())
                        .tags(toTags(timed.tags()))
                        .tag("error", "true")
                        .register(registry));
                    throw e;
                }
            }
        };
    }
    
    private Tags toTags(Map<String, String> tagMap) {
        return Tags.of(tagMap.entrySet().stream()
            .map(e -> Tag.of(e.getKey(), e.getValue()))
            .toList());
    }
}
```

### Usage

```java
Effect<Throwable, Result> measured = Effect.generate(ctx -> {
    ctx.perform(new MetricsCapability.IncrementCounter(
        "requests.total",
        Map.of("endpoint", "/api/users")
    ));
    
    return ctx.perform(new MetricsCapability.Timed<>(
        "request.duration",
        Map.of("endpoint", "/api/users"),
        new HttpCapability.Get("https://api.example.com/users")
    ));
}, handler);
```

---

## 8. Feature Flags

Dynamic feature toggling and A/B testing.

### Capability Definition

```java
public sealed interface FeatureFlagCapability<R> extends Capability<R> {
    record IsEnabled(String flagName, String userId) 
        implements FeatureFlagCapability<Boolean> {}
    
    record Variant(String experimentName, String userId) 
        implements FeatureFlagCapability<String> {}
    
    record GetValue(String flagName, String userId, String defaultValue) 
        implements FeatureFlagCapability<String> {}
}
```

### Handler Implementation

```java
public class FeatureFlagHandler 
        implements CapabilityHandler<FeatureFlagCapability<?>> {
    private final FeatureFlagProvider provider; // LaunchDarkly, etc.
    
    @Override
    @SuppressWarnings("unchecked")
    public <R> R handle(FeatureFlagCapability<?> capability) {
        return switch (capability) {
            case FeatureFlagCapability.IsEnabled enabled -> 
                (R) Boolean.valueOf(provider.isEnabled(
                    enabled.flagName(),
                    enabled.userId()
                ));
            
            case FeatureFlagCapability.Variant variant -> 
                (R) provider.getVariant(
                    variant.experimentName(),
                    variant.userId()
                );
            
            case FeatureFlagCapability.GetValue getValue -> 
                (R) provider.getValue(
                    getValue.flagName(),
                    getValue.userId(),
                    getValue.defaultValue()
                );
        };
    }
}
```

### Usage

```java
Effect<Throwable, Result> conditional = Effect.generate(ctx -> {
    boolean useNewAlgo = ctx.perform(
        new FeatureFlagCapability.IsEnabled("new-algorithm", userId)
    );
    
    if (useNewAlgo) {
        return processWithNewAlgorithm();
    } else {
        return processWithOldAlgorithm();
    }
}, handler);
```

---

## 9. Saga Pattern

Distributed transactions with automatic compensation.

### Capability Definition

```java
public sealed interface SagaCapability<R> extends Capability<R> {
    record Step<T>(
        String stepName,
        Capability<T> action,
        Function<T, Capability<Void>> compensation
    ) implements SagaCapability<T> {}
    
    record GetSteps() implements SagaCapability<List<String>> {}
}
```

### Handler Implementation

```java
public class SagaHandler implements CapabilityHandler<SagaCapability<?>> {
    private final CapabilityHandler<Capability<?>> delegate;
    private final Stack<CompensationAction> compensations = new Stack<>();
    
    record CompensationAction(String stepName, Capability<Void> action) {}
    
    @Override
    @SuppressWarnings("unchecked")
    public <R> R handle(SagaCapability<?> capability) throws Exception {
        return switch (capability) {
            case SagaCapability.Step<?> step -> {
                try {
                    R result = (R) delegate.handle(step.action());
                    compensations.push(new CompensationAction(
                        step.stepName(),
                        step.compensation().apply(result)
                    ));
                    yield result;
                } catch (Exception e) {
                    compensate();
                    throw e;
                }
            }
            
            case SagaCapability.GetSteps get -> 
                (R) compensations.stream()
                    .map(CompensationAction::stepName)
                    .toList();
        };
    }
    
    private void compensate() {
        while (!compensations.isEmpty()) {
            CompensationAction action = compensations.pop();
            try {
                delegate.handle(action.action());
            } catch (Exception e) {
                // Log compensation failure
            }
        }
    }
}
```

### Usage

```java
Effect<Throwable, Order> createOrder = Effect.generate(ctx -> {
    Payment payment = ctx.perform(new SagaCapability.Step<>(
        "charge-payment",
        new PaymentCapability.Charge(amount),
        p -> new PaymentCapability.Refund(p.id())
    ));
    
    Inventory inv = ctx.perform(new SagaCapability.Step<>(
        "reserve-inventory",
        new InventoryCapability.Reserve(items),
        i -> new InventoryCapability.Release(i.id())
    ));
    
    Shipment ship = ctx.perform(new SagaCapability.Step<>(
        "create-shipment",
        new ShipmentCapability.Create(address),
        s -> new ShipmentCapability.Cancel(s.id())
    ));
    
    return new Order(payment, inv, ship);
}, sagaHandler);
```

---

## 10. Dependency Injection

Type-safe dependency injection via capabilities.

### Capability Definition

```java
public sealed interface EnvCapability<R> extends Capability<R> {
    record Get<T>(Class<T> serviceType) implements EnvCapability<T> {}
    
    record GetNamed<T>(Class<T> serviceType, String name) 
        implements EnvCapability<T> {}
}
```

### Handler Implementation

```java
public class EnvHandler implements CapabilityHandler<EnvCapability<?>> {
    private final Map<Class<?>, Object> services = new HashMap<>();
    private final Map<String, Object> namedServices = new HashMap<>();
    
    public <T> EnvHandler register(Class<T> type, T instance) {
        services.put(type, instance);
        return this;
    }
    
    public <T> EnvHandler registerNamed(Class<T> type, String name, T instance) {
        namedServices.put(name, instance);
        return this;
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public <R> R handle(EnvCapability<?> capability) {
        return switch (capability) {
            case EnvCapability.Get<?> get -> {
                Object service = services.get(get.serviceType());
                if (service == null) {
                    throw new IllegalStateException(
                        "No service registered for: " + get.serviceType()
                    );
                }
                yield (R) service;
            }
            
            case EnvCapability.GetNamed<?> getNamed -> {
                Object service = namedServices.get(getNamed.name());
                if (service == null) {
                    throw new IllegalStateException(
                        "No service registered with name: " + getNamed.name()
                    );
                }
                yield (R) service;
            }
        };
    }
}
```

### Usage

```java
// Setup
EnvHandler env = new EnvHandler()
    .register(UserService.class, new UserServiceImpl())
    .register(EmailService.class, new EmailServiceImpl())
    .registerNamed(Database.class, "primary", primaryDb)
    .registerNamed(Database.class, "replica", replicaDb);

// Use
Effect<Throwable, Result> withDeps = Effect.generate(ctx -> {
    UserService users = ctx.perform(new EnvCapability.Get<>(UserService.class));
    EmailService email = ctx.perform(new EnvCapability.Get<>(EmailService.class));
    Database db = ctx.perform(new EnvCapability.GetNamed<>(Database.class, "primary"));
    
    User user = users.find(userId);
    email.send(user.email(), "Welcome!");
    return new Result(user);
}, env.widen());
```

---

## 11. Parallel Execution

Execute capabilities in parallel using built-in `zipPar` or custom strategies.

### Using Built-in zipPar (Recommended)

```java
import static com.cajunsystems.roux.Effects.*;

// Parallel execution of 2 capabilities
Effect<Throwable, Dashboard> dashboard = new GetUser("123")
    .toEffect()
    .zipPar(new GetOrders("123").toEffect(), Dashboard::new);

// Parallel execution of 3+ capabilities
Effect<Throwable, Summary> summary = par(
    new GetUser("123").toEffect(),
    new GetOrders("123").toEffect(),
    new GetPreferences("123").toEffect(),
    Summary::new
);

// Map over collection in parallel
List<Effect<Throwable, User>> userEffects = userIds.stream()
    .map(id -> new GetUser(id).toEffect())
    .toList();

// Then combine them (you can create a helper for this)
Effect<Throwable, List<User>> allUsers = userEffects.stream()
    .reduce(
        Effect.succeed(new ArrayList<>()),
        (acc, eff) -> acc.zipPar(eff, (list, user) -> {
            list.add(user);
            return list;
        })
    );
```

### Custom Parallel Capability (Advanced)

For more complex parallel patterns, you can define custom capabilities:

```java
public sealed interface ParallelCapability<R> extends Capability<R> {
    record ParMap<T, U>(
        List<T> items,
        Function<T, Capability<U>> f
    ) implements ParallelCapability<List<U>> {}
    
    record ParMapN<T, U>(
        List<T> items,
        Function<T, Capability<U>> f,
        int parallelism
    ) implements ParallelCapability<List<U>> {}
    
    record Race<T>(List<Capability<T>> operations) 
        implements ParallelCapability<T> {}
    
    record All<T>(List<Capability<T>> operations) 
        implements ParallelCapability<List<T>> {}
}
```

### Handler Implementation

```java
public class ParallelHandler implements CapabilityHandler<ParallelCapability<?>> {
    private final CapabilityHandler<Capability<?>> delegate;
    private final ExecutorService executor;
    
    @Override
    @SuppressWarnings("unchecked")
    public <R> R handle(ParallelCapability<?> capability) throws Exception {
        return switch (capability) {
            case ParallelCapability.ParMap<?, ?> parMap -> {
                List<CompletableFuture<?>> futures = parMap.items().stream()
                    .map(item -> CompletableFuture.supplyAsync(() -> {
                        try {
                            return delegate.handle(parMap.f().apply(item));
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }, executor))
                    .toList();
                
                yield (R) futures.stream()
                    .map(CompletableFuture::join)
                    .toList();
            }
            
            case ParallelCapability.ParMapN<?, ?> parMapN -> {
                // Use limited parallelism
                yield (R) parMapN.items().stream()
                    .parallel()
                    .map(item -> {
                        try {
                            return delegate.handle(parMapN.f().apply(item));
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .toList();
            }
            
            case ParallelCapability.Race<?> race -> {
                CompletableFuture<?>[] futures = race.operations().stream()
                    .map(op -> CompletableFuture.supplyAsync(() -> {
                        try {
                            return delegate.handle(op);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }, executor))
                    .toArray(CompletableFuture[]::new);
                
                yield (R) CompletableFuture.anyOf(futures).join();
            }
            
            case ParallelCapability.All<?> all -> {
                List<CompletableFuture<?>> futures = all.operations().stream()
                    .map(op -> CompletableFuture.supplyAsync(() -> {
                        try {
                            return delegate.handle(op);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }, executor))
                    .toList();
                
                yield (R) futures.stream()
                    .map(CompletableFuture::join)
                    .toList();
            }
        };
    }
}
```

### Usage

```java
Effect<Throwable, List<User>> parallel = Effect.generate(ctx -> {
    return ctx.perform(new ParallelCapability.ParMap<>(
        userIds,
        id -> new HttpCapability.Get("https://api.com/user/" + id)
    ));
}, handler);

// Race multiple sources
Effect<Throwable, String> fastest = Effect.generate(ctx -> {
    return ctx.perform(new ParallelCapability.Race<>(List.of(
        new HttpCapability.Get("https://api1.com/data"),
        new HttpCapability.Get("https://api2.com/data"),
        new HttpCapability.Get("https://api3.com/data")
    )));
}, handler);
```

---

## 12. Time Travel & Replay

Record and replay capability executions for debugging and testing.

### Capability Definition

```java
public sealed interface ReplayCapability<R> extends Capability<R> {
    record Record<T>(Capability<T> operation) 
        implements ReplayCapability<T> {}
    
    record GetHistory() 
        implements ReplayCapability<List<CapabilityExecution>> {}
    
    record Replay(List<CapabilityExecution> history) 
        implements ReplayCapability<Void> {}
}

record CapabilityExecution(
    Capability<?> capability,
    Object result,
    Optional<Throwable> error,
    Instant timestamp
) {}
```

### Handler Implementation

```java
public class ReplayHandler implements CapabilityHandler<ReplayCapability<?>> {
    private final CapabilityHandler<Capability<?>> delegate;
    private final List<CapabilityExecution> history = new ArrayList<>();
    private List<CapabilityExecution> replayHistory = null;
    private int replayIndex = 0;
    
    @Override
    @SuppressWarnings("unchecked")
    public <R> R handle(ReplayCapability<?> capability) throws Exception {
        return switch (capability) {
            case ReplayCapability.Record<?> record -> {
                if (replayHistory != null) {
                    // Replay mode
                    CapabilityExecution exec = replayHistory.get(replayIndex++);
                    if (exec.error().isPresent()) {
                        throw (Exception) exec.error().get();
                    }
                    yield (R) exec.result();
                } else {
                    // Record mode
                    try {
                        R result = (R) delegate.handle(record.operation());
                        history.add(new CapabilityExecution(
                            record.operation(),
                            result,
                            Optional.empty(),
                            Instant.now()
                        ));
                        yield result;
                    } catch (Exception e) {
                        history.add(new CapabilityExecution(
                            record.operation(),
                            null,
                            Optional.of(e),
                            Instant.now()
                        ));
                        throw e;
                    }
                }
            }
            
            case ReplayCapability.GetHistory get -> 
                (R) Collections.unmodifiableList(history);
            
            case ReplayCapability.Replay replay -> {
                replayHistory = replay.history();
                replayIndex = 0;
                yield (R) null;
            }
        };
    }
    
    public void saveHistory(Path path) throws IOException {
        // Serialize history to file
    }
    
    public void loadHistory(Path path) throws IOException {
        // Deserialize history from file
    }
}
```

### Usage

```java
// Record execution
ReplayHandler replayHandler = new ReplayHandler(productionHandler);

Effect<Throwable, Result> effect = Effect.generate(ctx -> {
    String data = ctx.perform(new ReplayCapability.Record<>(
        new HttpCapability.Get("https://api.example.com/data")
    ));
    
    User user = ctx.perform(new ReplayCapability.Record<>(
        new DbCapability.Query("SELECT * FROM users WHERE id = ?", List.of(123))
    ));
    
    return new Result(data, user);
}, replayHandler.widen());

runtime.unsafeRun(effect);

// Save history for later replay
replayHandler.saveHistory(Path.of("execution-history.json"));

// Later: Replay without hitting real services
ReplayHandler replayHandler2 = new ReplayHandler(null);
replayHandler2.loadHistory(Path.of("execution-history.json"));
replayHandler2.handle(new ReplayCapability.Replay(replayHandler2.getHistory()));

// Exact same execution, no network/db calls
runtime.unsafeRun(effect);
```

---

## Composing Multiple Handlers

You can combine multiple capability handlers for powerful effects:

```java
CapabilityHandler<Capability<?>> composedHandler = CapabilityHandler.compose(
    new TracingHandler(baseHandler),      // Trace all operations
    new MetricsHandler(baseHandler),      // Collect metrics
    new CacheHandler(baseHandler),        // Cache results
    new RetryHandler(baseHandler),        // Retry failures
    new CircuitBreakerHandler(baseHandler) // Prevent cascading failures
);

Effect<Throwable, Result> resilient = Effect.generate(ctx -> {
    // All handlers automatically applied!
    return ctx.perform(new HttpCapability.Get("url"));
}, composedHandler);
```

Each handler wraps the next, creating a powerful pipeline of cross-cutting concerns without changing your effect code!
