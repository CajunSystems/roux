# Custom Capabilities Example

This example shows how to define your own capabilities and handlers for algebraic effects in Roux.

## Define Your Capabilities

```java
// Define your domain-specific capabilities as a sealed interface
public sealed interface LogCapability<R> extends Capability<R> {
    record Info(String message) implements LogCapability<Void> {}
    record Debug(String message) implements LogCapability<Void> {}
    record Error(String message, Throwable error) implements LogCapability<Void> {}
}

public sealed interface HttpCapability<R> extends Capability<R> {
    record Get(String url) implements HttpCapability<String> {}
    record Post(String url, String body) implements HttpCapability<String> {}
}
```

## Implement Handlers

```java
// Production handler - performs real side effects
public class ProductionLogHandler implements CapabilityHandler<LogCapability<?>> {
    private final Logger logger = Logger.getLogger("App");
    
    @Override
    @SuppressWarnings("unchecked")
    public <R> R handle(LogCapability<?> capability) {
        return switch (capability) {
            case LogCapability.Info info -> {
                logger.info(info.message());
                yield (R) null;
            }
            case LogCapability.Debug debug -> {
                logger.fine(debug.message());
                yield (R) null;
            }
            case LogCapability.Error error -> {
                logger.severe(error.message());
                yield (R) null;
            }
        };
    }
}

// Test handler - captures logs for assertions
public class TestLogHandler implements CapabilityHandler<LogCapability<?>> {
    private final List<String> logs = new ArrayList<>();
    
    @Override
    @SuppressWarnings("unchecked")
    public <R> R handle(LogCapability<?> capability) {
        return switch (capability) {
            case LogCapability.Info info -> {
                logs.add("INFO: " + info.message());
                yield (R) null;
            }
            case LogCapability.Debug debug -> {
                logs.add("DEBUG: " + debug.message());
                yield (R) null;
            }
            case LogCapability.Error error -> {
                logs.add("ERROR: " + error.message());
                yield (R) null;
            }
        };
    }
    
    public List<String> getLogs() {
        return Collections.unmodifiableList(logs);
    }
}
```

## Use in Effects

```java
// Define your effect using the generator API
Effect<Throwable, String> workflow = Effect.generate(ctx -> {
    ctx.perform(new LogCapability.Info("Starting workflow"));
    
    String data = ctx.perform(new HttpCapability.Get("https://api.example.com/data"));
    
    ctx.perform(new LogCapability.Debug("Received: " + data));
    
    return data;
}, handler);

// Run with production handler
EffectRuntime runtime = DefaultEffectRuntime.create();
String result = runtime.unsafeRun(workflow);
```

## Testing

```java
@Test
void testWorkflow() throws Throwable {
    TestLogHandler logHandler = new TestLogHandler();
    TestHttpHandler httpHandler = new TestHttpHandler()
        .withResponse("https://api.example.com/data", "{\"result\":\"success\"}");
    
    // Compose handlers
    CapabilityHandler<Capability<?>> composedHandler = CapabilityHandler.compose(
        logHandler.widen(),
        httpHandler.widen()
    );
    
    Effect<Throwable, String> workflow = Effect.generate(ctx -> {
        ctx.perform(new LogCapability.Info("Starting"));
        String data = ctx.perform(new HttpCapability.Get("https://api.example.com/data"));
        ctx.perform(new LogCapability.Info("Done"));
        return data;
    }, composedHandler);
    
    String result = runtime.unsafeRun(workflow);
    
    assertEquals("{\"result\":\"success\"}", result);
    assertEquals(2, logHandler.getLogs().size());
}
```

## Benefits

1. **Separation of concerns**: Effect description is separate from interpretation
2. **Testability**: Swap handlers for testing without mocking
3. **Type safety**: Sealed interfaces ensure exhaustive pattern matching
4. **Composability**: Mix multiple capability types via `CapabilityHandler.compose()`
5. **Flexibility**: Same effect, different interpretations (prod, test, tracing, etc.)

## Advanced: Tracing Handler

```java
public class TracingHandler implements CapabilityHandler<Capability<?>> {
    private final CapabilityHandler<Capability<?>> delegate;
    private final List<Trace> traces = new ArrayList<>();
    
    public record Trace(Capability<?> capability, Object result, long durationNanos) {}
    
    @Override
    public <R> R handle(Capability<?> capability) throws Exception {
        long start = System.nanoTime();
        R result = delegate.handle(capability);
        long duration = System.nanoTime() - start;
        traces.add(new Trace(capability, result, duration));
        return result;
    }
    
    public List<Trace> getTraces() {
        return Collections.unmodifiableList(traces);
    }
}

// Wrap any handler with tracing
TracingHandler tracer = new TracingHandler(productionHandler);
Effect<Throwable, String> effect = Effect.generate(ctx -> {
    // ... your code
}, tracer);

runtime.unsafeRun(effect);
tracer.getTraces().forEach(System.out::println);
```

## Using the Capability-Effect Bridge

Convert capabilities to effects to use all Effect operators:

```java
import static com.cajunsystems.roux.Effects.*;

// Convert capability to effect
Effect<Throwable, String> fetchEffect = new MyCapability.Fetch("https://api.com/data")
    .toEffect()
    .map(String::toUpperCase)
    .catchAll(e -> Effect.succeed("default"));

// Run with handler
String result = runtime.unsafeRunWithHandler(fetchEffect, handler);

// Parallel execution
Effect<Throwable, Result> parallel = new MyCapability.Fetch("url1")
    .toEffect()
    .zipPar(new MyCapability.Fetch("url2").toEffect(), Result::new);

// Or with static helpers for 3+ effects
Effect<Throwable, Summary> summary = par(
    new MyCapability.Fetch("url1").toEffect(),
    new MyCapability.Fetch("url2").toEffect(),
    new MyCapability.Fetch("url3").toEffect(),
    Summary::new
);

// Use in generator context
Effect<Throwable, String> workflow = Effect.generate(ctx -> {
    // Lift capability to effect for composition
    Effect<Throwable, String> dataEffect = ctx.lift(new MyCapability.Fetch("url"))
        .map(String::trim)
        .map(String::toUpperCase);
    
    String data = ctx.yield(dataEffect);
    ctx.perform(new MyCapability.Log("Got: " + data));
    
    return data;
}, handler);
```

**Benefits:**
- All Effect operators work (map, flatMap, retry, timeout, zipPar, etc.)
- Handler is implicit - provided at runtime
- Clean, composable API
- Mix generator style with functional style
