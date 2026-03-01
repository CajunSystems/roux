# Example: Resilience Patterns

Common reliability patterns — retry, timeout, fallback, hedging, and
observability — all expressed as pure `Effect` combinators and composed
without touching application logic.

---

## Domain Setup

```java
record Quote(String provider, double price) {}
record ExchangeRate(String pair, double rate) {}
```

---

## 1. Retry with Exponential Back-off

`retryWithDelay` retries after each failure. Chain multiple calls for
geometrically increasing waits.

```java
import static com.cajunsystems.roux.Effects.*;
import java.time.Duration;

sealed interface PricingCapability<R> extends Capability<R> {
    record FetchQuote(String symbol, String provider)
            implements PricingCapability<Quote> {}
}

/**
 * Fetch a quote from a single provider, retrying up to 3 times.
 * First retry after 100 ms, second after 200 ms, third after 400 ms.
 *
 * retryWithDelay(n, d) retries n additional times, each separated by d.
 * To express exponential back-off compose two retryWithDelay calls:
 *   attempt 1 → immediate (the original call)
 *   retry  1  → +100 ms
 *   retry  2  → +200 ms
 *   retry  3  → +400 ms
 */
Effect<Throwable, Quote> fetchWithRetry(String symbol, String provider) {
    return new PricingCapability.FetchQuote(symbol, provider)
            .<Throwable, Quote>toEffect()
            .retryWithDelay(1, Duration.ofMillis(100))   // retry 1 after 100 ms
            .retryWithDelay(1, Duration.ofMillis(200))   // retry 2 after 200 ms
            .retryWithDelay(1, Duration.ofMillis(400));  // retry 3 after 400 ms
}
```

---

## 2. Timeout Per Call

Wrap any effect in `.timeout(Duration)` to bound its execution time.
The effect fails immediately with `TimeoutException` if the duration elapses.

```java
sealed interface FxCapability<R> extends Capability<R> {
    record FetchRate(String pair) implements FxCapability<ExchangeRate> {}
}

/** Rate fetch must complete within 2 seconds or the whole pipeline fails. */
Effect<Throwable, ExchangeRate> timedRateFetch(String pair) {
    return new FxCapability.FetchRate(pair)
            .<Throwable, ExchangeRate>toEffect()
            .timeout(Duration.ofSeconds(2));
}
```

---

## 3. Fallback on Failure

`catchAll` provides a graceful degradation path when the primary effect fails.
Chain it after a `timeout` to give a cached value when the live feed is slow.

```java
sealed interface CacheCapability<R> extends Capability<R> {
    record GetCachedRate(String pair) implements CacheCapability<ExchangeRate> {}
}

/**
 * Try the live FX feed (2 s limit); if it times out or throws, serve the
 * cached rate instead.
 */
Effect<Throwable, ExchangeRate> rateWithFallback(String pair) {
    Effect<Throwable, ExchangeRate> live =
            new FxCapability.FetchRate(pair)
                    .<Throwable, ExchangeRate>toEffect()
                    .timeout(Duration.ofSeconds(2));

    Effect<Throwable, ExchangeRate> cached =
            new CacheCapability.GetCachedRate(pair)
                    .<Throwable, ExchangeRate>toEffect();

    return live.catchAll(__ -> cached);
}
```

---

## 4. Hedge with `race`

Issue the same request to multiple providers simultaneously and take the
first to respond. `Effects.race(ea, eb)` — or `Effects.race(List)` for more
than two — cancels the slower effects once a winner is found.

```java
/**
 * Ask three price providers in parallel; accept whichever responds first.
 * Demonstrates hedging: send redundant requests to reduce tail latency.
 */
Effect<Throwable, Quote> hedgedQuote(String symbol) {
    var fast    = new PricingCapability.FetchQuote(symbol, "ProviderA").<Throwable, Quote>toEffect();
    var backup1 = new PricingCapability.FetchQuote(symbol, "ProviderB").<Throwable, Quote>toEffect();
    var backup2 = new PricingCapability.FetchQuote(symbol, "ProviderC").<Throwable, Quote>toEffect();

    return race(List.of(fast, backup1, backup2));
}
```

---

## 5. `tap` / `tapError` for Observability

Attach side-effects for metrics or logging at any point in the chain.
Neither `tap` nor `tapError` changes the value or error — they are purely
observational.

```java
sealed interface MetricsCapability<R> extends Capability<R> {
    record RecordLatency(String op, long ms)    implements MetricsCapability<Void> {}
    record RecordError(String op, String cause) implements MetricsCapability<Void> {}
}

/**
 * Full pipeline combining retry, timeout, fallback, and observability.
 *
 *  - Emit a metric on success (tap)
 *  - Log the error class on any failure path (tapError)
 *  - Retry the live feed once before giving up and hitting the cache
 */
Effect<Throwable, ExchangeRate> resilientRateFetch(String pair) {
    long start = System.currentTimeMillis();

    Effect<Throwable, ExchangeRate> live =
            new FxCapability.FetchRate(pair)
                    .<Throwable, ExchangeRate>toEffect()
                    .retry(1)                                    // one immediate retry
                    .timeout(Duration.ofSeconds(3));

    Effect<Throwable, ExchangeRate> cached =
            new CacheCapability.GetCachedRate(pair)
                    .<Throwable, ExchangeRate>toEffect();

    return live
            .catchAll(__ -> cached)                             // fallback to cache
            .tap(rate -> {                                       // success metric
                long ms = System.currentTimeMillis() - start;
                System.out.printf("[metrics] fx.fetch.ok pair=%s latency=%dms%n", pair, ms);
            })
            .tapError(err ->                                     // failure metric
                System.err.printf("[metrics] fx.fetch.err pair=%s cause=%s%n",
                        pair, err.getClass().getSimpleName())
            );
}
```

---

## 6. Composing Multiple Handlers

Real applications combine domain-specific handlers. Use `CapabilityHandler.compose()`
to wire them together without the handlers knowing about each other.

```java
// Production handler for live FX data
class LiveFxHandler implements CapabilityHandler<FxCapability<?>> {
    private final FxGateway gateway;

    @Override
    @SuppressWarnings("unchecked")
    public <R> R handle(FxCapability<?> cap) throws Exception {
        return switch (cap) {
            case FxCapability.FetchRate fr ->
                    (R) gateway.fetchRate(fr.pair());
        };
    }
}

// Production handler for the cache
class CacheHandler implements CapabilityHandler<CacheCapability<?>> {
    private final RateCache cache;

    @Override
    @SuppressWarnings("unchecked")
    public <R> R handle(CacheCapability<?> cap) throws Exception {
        return switch (cap) {
            case CacheCapability.GetCachedRate gc ->
                    (R) cache.get(gc.pair());
        };
    }
}

// Compose for production
CapabilityHandler<Capability<?>> productionHandler =
        CapabilityHandler.compose(new LiveFxHandler(gateway), new CacheHandler(cache));
```

---

## 7. Test Handler — All Patterns in One Test

```java
@Test
void resilientRateFetch_liveTimeout_servesCache() throws Throwable {
    String pair = "USD/EUR";
    ExchangeRate cached = new ExchangeRate(pair, 0.92);

    // Track which capabilities were invoked
    AtomicInteger liveCallCount  = new AtomicInteger();
    AtomicInteger cacheCallCount = new AtomicInteger();

    CapabilityHandler<Capability<?>> handler = CapabilityHandler.builder()

        // Simulate a slow live feed — always times out
        .on(FxCapability.FetchRate.class, cap -> {
            liveCallCount.incrementAndGet();
            Thread.sleep(10_000); // much longer than the 3-s timeout
            return new ExchangeRate(cap.pair(), 0.91);
        })

        // Cache always returns instantly
        .on(CacheCapability.GetCachedRate.class, cap -> {
            cacheCallCount.incrementAndGet();
            return cached;
        })

        .build();

    try (var runtime = DefaultEffectRuntime.create()) {
        ExchangeRate result = runtime.unsafeRunWithHandler(
                resilientRateFetch(pair),
                handler
        );

        // Cache was served
        assertEquals(0.92, result.rate(), 1e-9);
        assertEquals(pair,  result.pair());

        // Live was tried twice (original + 1 retry) before timeout, then cache once
        assertEquals(2, liveCallCount.get());
        assertEquals(1, cacheCallCount.get());
    }
}

@Test
void hedgedQuote_fastestProviderWins() throws Throwable {
    // ProviderA sleeps 500 ms, ProviderB responds at 50 ms, ProviderC sleeps 300 ms
    CapabilityHandler<Capability<?>> handler = CapabilityHandler.builder()
        .on(PricingCapability.FetchQuote.class, cap -> switch (cap.provider()) {
            case "ProviderA" -> { Thread.sleep(500); yield new Quote("ProviderA", 100.5); }
            case "ProviderB" -> { Thread.sleep(50);  yield new Quote("ProviderB", 100.3); }
            default          -> { Thread.sleep(300); yield new Quote("ProviderC", 100.4); }
        })
        .build();

    try (var runtime = DefaultEffectRuntime.create()) {
        Quote winner = runtime.unsafeRunWithHandler(hedgedQuote("AAPL"), handler);
        assertEquals("ProviderB", winner.provider());
    }
}
```

---

## Key Patterns Demonstrated

| Pattern | API | Notes |
|---------|-----|-------|
| Immediate retry | `.retry(n)` | No delay; best for idempotent, cheap operations |
| Retry with back-off | `.retryWithDelay(n, d)` | Chain multiple calls for geometric delays |
| Per-call timeout | `.timeout(Duration)` | Fails with `TimeoutException`; combine with fallback |
| Graceful degradation | `.catchAll(__ -> fallback)` | Run alternate effect on any error |
| Hedging | `Effects.race(List)` | First to succeed wins; slower attempts cancelled |
| Success metric | `.tap(value -> ...)` | Non-intrusive; value passes through unchanged |
| Failure metric | `.tapError(err -> ...)` | Non-intrusive; error passes through unchanged |
| Handler composition | `CapabilityHandler.compose(...)` | Domain handlers stay independent |
