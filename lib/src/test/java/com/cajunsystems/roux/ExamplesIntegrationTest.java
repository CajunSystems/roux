package com.cajunsystems.roux;

import com.cajunsystems.roux.capability.Capability;
import com.cajunsystems.roux.capability.CapabilityHandler;
import com.cajunsystems.roux.data.Either;
import com.cajunsystems.roux.data.Unit;
import com.cajunsystems.roux.exception.TimeoutException;
import com.cajunsystems.roux.runtime.DefaultEffectRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.cajunsystems.roux.Effects.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Runnable integration tests that verify the non-trivial example patterns
 * documented in examples/OrderProcessing.md, examples/ResiliencePatterns.md,
 * and examples/BatchProcessing.md compile and behave correctly against the
 * real 0.2.0 API.
 */
class ExamplesIntegrationTest {

    private DefaultEffectRuntime runtime;

    @BeforeEach
    void setUp() { runtime = DefaultEffectRuntime.create(); }

    @AfterEach
    void tearDown() { runtime.close(); }

    // =========================================================================
    // Scenario 1 — Order Processing Pipeline
    // =========================================================================

    @Nested
    class OrderProcessingScenario {

        // --- domain model ---------------------------------------------------
        record User(String id, String email, boolean verified) {}
        record Item(String sku, int quantity) {}
        record InventoryResult(String sku, boolean available, int stock) {}
        record PaymentResult(String txId, boolean success) {}
        record Order(String orderId, String userId, List<Item> items, PaymentResult payment) {}

        // --- capabilities ---------------------------------------------------
        sealed interface OrderCapability<R> extends Capability<R> {
            record FetchUser(String userId)                implements OrderCapability<User> {}
            record CheckInventory(String sku, int qty)     implements OrderCapability<InventoryResult> {}
            record ChargeCard(String userId, double amount) implements OrderCapability<PaymentResult> {}
            record SendConfirmation(String email)          implements OrderCapability<Void> {}
            record AuditLog(String event)                  implements OrderCapability<Void> {}
        }

        /**
         * Mirrors OrderService.placeOrder() from examples/OrderProcessing.md.
         *
         *   1. Fetch user + check all inventory concurrently  (parAll / zipPar)
         *   2. Validate user + stock                          (fail-fast)
         *   3. Charge card with timeout + retry
         *   4. Send confirmation + audit in parallel          (zipPar)
         *   5. tapError for failure audit
         */
        Effect<Throwable, Order> placeOrder(String userId, List<Item> items, double total) {

            // toEffect() has a single type argument <E extends Throwable>;
            // R is already fixed by the Capability<R> implementation.
            Effect<Throwable, User> fetchUser =
                    new OrderCapability.FetchUser(userId).<Throwable>toEffect();

            List<Effect<Throwable, InventoryResult>> inventoryEffects = new ArrayList<>();
            for (Item item : items) {
                inventoryEffects.add(
                        new OrderCapability.CheckInventory(item.sku(), item.quantity())
                                .<Throwable>toEffect());
            }
            Effect<Throwable, List<InventoryResult>> checkInventory = parAll(inventoryEffects);

            return fetchUser
                    .zipPar(checkInventory, (user, inventory) -> {
                        if (!user.verified()) {
                            throw new IllegalStateException("User not verified: " + userId);
                        }
                        List<String> outOfStock = new ArrayList<>();
                        for (InventoryResult r : inventory) {
                            if (!r.available()) outOfStock.add(r.sku());
                        }
                        if (!outOfStock.isEmpty()) {
                            throw new IllegalStateException("Out of stock: " + outOfStock);
                        }
                        return user;
                    })
                    .flatMap(user -> {
                        Effect<Throwable, Order> chargeAndBuild =
                                new OrderCapability.ChargeCard(userId, total)
                                        .<Throwable>toEffect()
                                        .timeout(Duration.ofSeconds(5))
                                        .retry(2)
                                        .tap(payment -> {
                                            if (!payment.success()) {
                                                throw new IllegalStateException(
                                                        "Payment declined: " + payment.txId());
                                            }
                                        })
                                        .map(payment -> new Order(
                                                UUID.randomUUID().toString(),
                                                user.id(),
                                                items,
                                                payment));
                        return chargeAndBuild;
                    })
                    .flatMap(order -> {
                        Effect<Throwable, Void> notify =
                                new OrderCapability.SendConfirmation(order.userId())
                                        .<Throwable>toEffect();
                        Effect<Throwable, Void> audit =
                                new OrderCapability.AuditLog("order.placed")
                                        .<Throwable>toEffect();
                        return notify.zipPar(audit, (a, b) -> order);
                    })
                    .tapError(err ->
                            System.err.printf("[audit] order failed user=%s reason=%s%n",
                                    userId, err.getMessage()));
        }

        @Test
        void successPath_producesOrderWithCorrectData() throws Throwable {
            Map<String, User> users = Map.of("u1",
                    new User("u1", "alice@example.com", true));
            Map<String, Integer> inventory = Map.of("SKU-A", 10, "SKU-B", 5);
            List<String> emails  = new ArrayList<>();
            List<String> audits  = new ArrayList<>();

            CapabilityHandler<Capability<?>> handler = CapabilityHandler.builder()
                    .on(OrderCapability.FetchUser.class, cap ->
                            Optional.ofNullable(users.get(cap.userId()))
                                    .orElseThrow(() -> new RuntimeException("user not found")))
                    .on(OrderCapability.CheckInventory.class, cap -> {
                        int stock = inventory.getOrDefault(cap.sku(), 0);
                        return new InventoryResult(cap.sku(), stock >= cap.qty(), stock);
                    })
                    .on(OrderCapability.ChargeCard.class, cap ->
                            new PaymentResult("TX-" + UUID.randomUUID(), true))
                    .on(OrderCapability.SendConfirmation.class, cap -> {
                        emails.add(cap.email()); return null;
                    })
                    .on(OrderCapability.AuditLog.class, cap -> {
                        audits.add(cap.event()); return null;
                    })
                    .build();

            Order order = runtime.unsafeRunWithHandler(
                    placeOrder("u1", List.of(new Item("SKU-A", 2)), 49.99), handler);

            assertNotNull(order.orderId());
            assertEquals("u1", order.userId());
            assertTrue(order.payment().success());
            assertEquals(List.of("u1"), emails);
            assertTrue(audits.contains("order.placed"));
        }

        @Test
        void unverifiedUser_failsBeforeCharge() {
            AtomicInteger chargeCount = new AtomicInteger();

            CapabilityHandler<Capability<?>> handler = CapabilityHandler.builder()
                    .on(OrderCapability.FetchUser.class, cap ->
                            new User(cap.userId(), "bob@example.com", false))
                    .on(OrderCapability.CheckInventory.class, cap ->
                            new InventoryResult(cap.sku(), true, 99))
                    .on(OrderCapability.ChargeCard.class, cap -> {
                        chargeCount.incrementAndGet();
                        return new PaymentResult("TX-1", true);
                    })
                    .on(OrderCapability.AuditLog.class, cap -> null)
                    .build();

            IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                    runtime.unsafeRunWithHandler(
                            placeOrder("u2", List.of(new Item("SKU-X", 1)), 9.99), handler));

            assertTrue(ex.getMessage().contains("not verified"));
            assertEquals(0, chargeCount.get());
        }

        @Test
        void outOfStock_failsBeforeCharge() {
            AtomicInteger chargeCount = new AtomicInteger();

            CapabilityHandler<Capability<?>> handler = CapabilityHandler.builder()
                    .on(OrderCapability.FetchUser.class, cap ->
                            new User(cap.userId(), "alice@example.com", true))
                    .on(OrderCapability.CheckInventory.class, cap ->
                            new InventoryResult(cap.sku(), false, 0))
                    .on(OrderCapability.ChargeCard.class, cap -> {
                        chargeCount.incrementAndGet();
                        return new PaymentResult("TX-X", true);
                    })
                    .on(OrderCapability.AuditLog.class, cap -> null)
                    .build();

            IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                    runtime.unsafeRunWithHandler(
                            placeOrder("u3", List.of(new Item("SKU-MISSING", 1)), 5.00), handler));

            assertTrue(ex.getMessage().contains("Out of stock"));
            assertEquals(0, chargeCount.get());
        }

        @Test
        void chargeRetry_succeedsOnSecondAttempt() throws Throwable {
            AtomicInteger attempts = new AtomicInteger();

            CapabilityHandler<Capability<?>> handler = CapabilityHandler.builder()
                    .on(OrderCapability.FetchUser.class, cap ->
                            new User(cap.userId(), "alice@example.com", true))
                    .on(OrderCapability.CheckInventory.class, cap ->
                            new InventoryResult(cap.sku(), true, 10))
                    .on(OrderCapability.ChargeCard.class, cap -> {
                        if (attempts.incrementAndGet() < 2) {
                            throw new RuntimeException("transient payment error");
                        }
                        return new PaymentResult("TX-OK", true);
                    })
                    .on(OrderCapability.SendConfirmation.class, cap -> null)
                    .on(OrderCapability.AuditLog.class, cap -> null)
                    .build();

            Order order = runtime.unsafeRunWithHandler(
                    placeOrder("u1", List.of(new Item("SKU-A", 1)), 20.00), handler);

            assertNotNull(order.orderId());
            assertEquals(2, attempts.get());
        }
    }

    // =========================================================================
    // Scenario 2 — Resilience Patterns
    // =========================================================================

    @Nested
    class ResiliencePatternsScenario {

        record Quote(String provider, double price) {}
        record ExchangeRate(String pair, double rate) {}

        sealed interface PricingCapability<R> extends Capability<R> {
            record FetchQuote(String symbol, String provider)
                    implements PricingCapability<Quote> {}
        }

        sealed interface FxCapability<R> extends Capability<R> {
            record FetchRate(String pair) implements FxCapability<ExchangeRate> {}
        }

        sealed interface CacheCapability<R> extends Capability<R> {
            record GetCachedRate(String pair) implements CacheCapability<ExchangeRate> {}
        }

        @Test
        void retryWithDelay_succeedsAfterTransientFailures() throws Throwable {
            AtomicInteger attempts = new AtomicInteger();

            CapabilityHandler<Capability<?>> handler = CapabilityHandler.builder()
                    .on(FxCapability.FetchRate.class, cap -> {
                        if (attempts.incrementAndGet() < 3) {
                            throw new RuntimeException("transient");
                        }
                        return new ExchangeRate(cap.pair(), 1.08);
                    })
                    .build();

            Effect<Throwable, ExchangeRate> effect =
                    new FxCapability.FetchRate("EUR/USD")
                            .<Throwable>toEffect()
                            .retryWithDelay(2, Duration.ofMillis(10));

            ExchangeRate rate = runtime.unsafeRunWithHandler(effect, handler);

            assertEquals(1.08, rate.rate(), 1e-9);
            assertEquals(3, attempts.get());   // 1 original + 2 retries
        }

        @Test
        void timeout_failsWithTimeoutException() {
            CapabilityHandler<Capability<?>> handler = CapabilityHandler.builder()
                    .on(FxCapability.FetchRate.class, cap -> {
                        Thread.sleep(10_000);
                        return new ExchangeRate(cap.pair(), 0.0);
                    })
                    .build();

            Effect<Throwable, ExchangeRate> effect =
                    new FxCapability.FetchRate("USD/EUR")
                            .<Throwable>toEffect()
                            .timeout(Duration.ofMillis(200));

            assertThrows(TimeoutException.class, () ->
                    runtime.unsafeRunWithHandler(effect, handler));
        }

        @Test
        void fallback_servesCache_whenLiveFeedTimesOut() throws Throwable {
            ExchangeRate cached = new ExchangeRate("USD/EUR", 0.92);
            AtomicInteger liveAttempts  = new AtomicInteger();
            AtomicInteger cacheAttempts = new AtomicInteger();

            CapabilityHandler<Capability<?>> handler = CapabilityHandler.builder()
                    .on(FxCapability.FetchRate.class, cap -> {
                        liveAttempts.incrementAndGet();
                        Thread.sleep(10_000);
                        return new ExchangeRate(cap.pair(), 0.91);
                    })
                    .on(CacheCapability.GetCachedRate.class, cap -> {
                        cacheAttempts.incrementAndGet();
                        return cached;
                    })
                    .build();

            Effect<Throwable, ExchangeRate> live =
                    new FxCapability.FetchRate("USD/EUR")
                            .<Throwable>toEffect()
                            .timeout(Duration.ofMillis(200));

            Effect<Throwable, ExchangeRate> withFallback =
                    live.catchAll(__ ->
                            new CacheCapability.GetCachedRate("USD/EUR")
                                    .<Throwable>toEffect());

            ExchangeRate result = runtime.unsafeRunWithHandler(withFallback, handler);

            assertEquals(0.92, result.rate(), 1e-9);
            assertEquals(1, liveAttempts.get());
            assertEquals(1, cacheAttempts.get());
        }

        @Test
        void race_fastestProviderWins() throws Throwable {
            CapabilityHandler<Capability<?>> handler = CapabilityHandler.builder()
                    .on(PricingCapability.FetchQuote.class, cap -> {
                        switch (cap.provider()) {
                            case "ProviderA": Thread.sleep(500); return new Quote("ProviderA", 100.5);
                            case "ProviderB": Thread.sleep(50);  return new Quote("ProviderB", 100.3);
                            default:          Thread.sleep(300); return new Quote("ProviderC", 100.4);
                        }
                    })
                    .build();

            List<Effect<Throwable, Quote>> candidates = List.of(
                    new PricingCapability.FetchQuote("AAPL", "ProviderA").<Throwable>toEffect(),
                    new PricingCapability.FetchQuote("AAPL", "ProviderB").<Throwable>toEffect(),
                    new PricingCapability.FetchQuote("AAPL", "ProviderC").<Throwable>toEffect()
            );

            Quote winner = runtime.unsafeRunWithHandler(race(candidates), handler);
            assertEquals("ProviderB", winner.provider());
        }

        @Test
        void tap_doesNotAlterSuccessValue() throws Throwable {
            List<String> log = new ArrayList<>();

            CapabilityHandler<Capability<?>> handler = CapabilityHandler.builder()
                    .on(FxCapability.FetchRate.class, cap ->
                            new ExchangeRate(cap.pair(), 1.10))
                    .build();

            Effect<Throwable, ExchangeRate> effect =
                    new FxCapability.FetchRate("GBP/USD")
                            .<Throwable>toEffect()
                            .tap(rate -> log.add("got: " + rate.rate()))
                            .tapError(err -> log.add("err: " + err.getMessage()));

            ExchangeRate rate = runtime.unsafeRunWithHandler(effect, handler);

            assertEquals(1.10, rate.rate(), 1e-9);
            assertEquals(List.of("got: 1.1"), log);
        }

        @Test
        void tapError_observesErrorWithoutSwallowingIt() {
            List<String> errorLog = new ArrayList<>();

            CapabilityHandler<Capability<?>> handler = CapabilityHandler.builder()
                    .on(FxCapability.FetchRate.class, cap -> {
                        throw new RuntimeException("feed down");
                    })
                    .build();

            Effect<Throwable, ExchangeRate> effect =
                    new FxCapability.FetchRate("USD/JPY")
                            .<Throwable>toEffect()
                            .tapError(err -> errorLog.add(err.getMessage()));

            RuntimeException ex = assertThrows(RuntimeException.class, () ->
                    runtime.unsafeRunWithHandler(effect, handler));

            assertEquals("feed down", ex.getMessage());
            assertEquals(List.of("feed down"), errorLog);
        }
    }

    // =========================================================================
    // Scenario 3 — Batch Processing Pipeline
    // =========================================================================

    @Nested
    class BatchProcessingScenario {

        record CsvRow(int lineNumber, String rawData) {}
        record ParsedRecord(int lineNumber, String customerId, double amount, String currency) {}
        record EnrichedRecord(ParsedRecord record, double amountUsd) {}
        record ProcessingReport(List<EnrichedRecord> succeeded, List<String> failed) {}

        sealed interface BatchCapability<R> extends Capability<R> {
            record ParseRow(CsvRow row)                       implements BatchCapability<ParsedRecord> {}
            record ConvertToUsd(double amount, String currency) implements BatchCapability<Double> {}
            record PersistRecord(EnrichedRecord record)       implements BatchCapability<Void> {}
            record WriteSummary(ProcessingReport report)      implements BatchCapability<Void> {}
        }

        /**
         * Mirrors the pipeline from examples/BatchProcessing.md.
         *
         *   1. traverse — parse all rows, collecting errors via Either (no abort)
         *   2. parAll   — enrich valid records concurrently
         *   3. sequence — persist enriched records one at a time (ordered)
         *   4. when     — write summary only when the batch is non-empty
         */
        Effect<Throwable, ProcessingReport> processBatch(List<CsvRow> rows) {

            // Step 1: parse each row; .attempt() converts failure to Either so the
            // whole traverse does not short-circuit on a bad row.
            Effect<Throwable, List<Either<String, ParsedRecord>>> parseAll =
                    traverse(rows, row -> {
                        Effect<Throwable, Either<Throwable, ParsedRecord>> attempted =
                                new BatchCapability.ParseRow(row).<Throwable>toEffect().attempt();
                        Effect<Throwable, Either<String, ParsedRecord>> labelled =
                                attempted.map(e -> e.mapLeft(err ->
                                        "line " + row.lineNumber() + ": " + err.getMessage()));
                        return labelled;
                    });

            return parseAll.flatMap(results -> {

                List<ParsedRecord> valid = new ArrayList<>();
                List<String> failures   = new ArrayList<>();
                for (Either<String, ParsedRecord> r : results) {
                    if (r.isRight()) valid.add(r.getOrElse((ParsedRecord) null));
                    else             failures.add(r.fold(msg -> msg, __ -> ""));
                }

                // Step 2: enrich in parallel
                List<Effect<Throwable, EnrichedRecord>> enrichEffects = new ArrayList<>();
                for (ParsedRecord rec : valid) {
                    Effect<Throwable, EnrichedRecord> e =
                            new BatchCapability.ConvertToUsd(rec.amount(), rec.currency())
                                    .<Throwable>toEffect()
                                    .map(usd -> new EnrichedRecord(rec, usd));
                    enrichEffects.add(e);
                }
                Effect<Throwable, List<EnrichedRecord>> enrichAll =
                        valid.isEmpty() ? Effect.succeed(List.of()) : parAll(enrichEffects);

                return enrichAll.flatMap(enriched -> {

                    // Step 3: persist sequentially
                    List<Effect<Throwable, Void>> persistEffects = new ArrayList<>();
                    for (EnrichedRecord er : enriched) {
                        persistEffects.add(
                                new BatchCapability.PersistRecord(er).<Throwable>toEffect());
                    }
                    Effect<Throwable, List<Void>> persistAll =
                            persistEffects.isEmpty()
                                    ? Effect.succeed(List.of())
                                    : sequence(persistEffects);

                    return persistAll.flatMap(ignored -> {
                        ProcessingReport report = new ProcessingReport(enriched, failures);

                        // Step 4: conditional summary
                        boolean hasSomething = !enriched.isEmpty() || !failures.isEmpty();
                        Effect<Throwable, Unit> summary = Effect.when(hasSomething,
                                new BatchCapability.WriteSummary(report)
                                        .<Throwable>toEffect());

                        return summary.map(u -> report);
                    });
                });
            });
        }

        @Test
        void mixedRows_twoValidOneInvalid() throws Throwable {
            List<CsvRow> rows = List.of(
                    new CsvRow(1, "C001,50.00,USD"),
                    new CsvRow(2, "INVALID_DATA"),
                    new CsvRow(3, "C002,30.00,EUR"));

            List<EnrichedRecord> persisted = new ArrayList<>();
            List<ProcessingReport> reports = new ArrayList<>();

            CapabilityHandler<Capability<?>> handler = CapabilityHandler.builder()
                    .on(BatchCapability.ParseRow.class, cap -> {
                        String[] p = cap.row().rawData().split(",");
                        if (p.length != 3) throw new IllegalArgumentException("bad format");
                        return new ParsedRecord(
                                cap.row().lineNumber(), p[0],
                                Double.parseDouble(p[1]), p[2]);
                    })
                    .on(BatchCapability.ConvertToUsd.class, cap ->
                            switch (cap.currency()) {
                                case "USD" -> cap.amount();
                                case "EUR" -> cap.amount() * 1.08;
                                default -> throw new IllegalArgumentException("unknown: " + cap.currency());
                            })
                    .on(BatchCapability.PersistRecord.class, cap -> {
                        persisted.add(cap.record()); return null;
                    })
                    .on(BatchCapability.WriteSummary.class, cap -> {
                        reports.add(cap.report()); return null;
                    })
                    .build();

            ProcessingReport report = runtime.unsafeRunWithHandler(processBatch(rows), handler);

            assertEquals(2, report.succeeded().size());
            assertEquals(1, report.failed().size());
            assertTrue(report.failed().get(0).contains("line 2"));
            assertEquals(2, persisted.size());
            assertEquals(1, reports.size());

            double eurAmount = report.succeeded().stream()
                    .filter(e -> "C002".equals(e.record().customerId()))
                    .findFirst().orElseThrow().amountUsd();
            assertEquals(30.0 * 1.08, eurAmount, 1e-9);
        }

        @Test
        void allInvalid_summaryStillWritten_enrichAndPersistSkipped() throws Throwable {
            AtomicInteger enrichCount  = new AtomicInteger();
            AtomicInteger persistCount = new AtomicInteger();
            AtomicInteger summaryCount = new AtomicInteger();

            CapabilityHandler<Capability<?>> handler = CapabilityHandler.builder()
                    .on(BatchCapability.ParseRow.class, cap -> {
                        throw new IllegalArgumentException("parse error");
                    })
                    .on(BatchCapability.ConvertToUsd.class, cap -> {
                        enrichCount.incrementAndGet(); return cap.amount();
                    })
                    .on(BatchCapability.PersistRecord.class, cap -> {
                        persistCount.incrementAndGet(); return null;
                    })
                    .on(BatchCapability.WriteSummary.class, cap -> {
                        summaryCount.incrementAndGet(); return null;
                    })
                    .build();

            ProcessingReport report = runtime.unsafeRunWithHandler(
                    processBatch(List.of(new CsvRow(1, "BAD"), new CsvRow(2, "ALSO_BAD"))),
                    handler);

            assertEquals(0, report.succeeded().size());
            assertEquals(2, report.failed().size());
            assertEquals(0, enrichCount.get());
            assertEquals(0, persistCount.get());
            assertEquals(1, summaryCount.get());   // failures still trigger the summary
        }

        @Test
        void emptyInput_noOpsAndNoSummary() throws Throwable {
            AtomicInteger summaryCount = new AtomicInteger();

            CapabilityHandler<Capability<?>> handler = CapabilityHandler.builder()
                    .on(BatchCapability.WriteSummary.class, cap -> {
                        summaryCount.incrementAndGet(); return null;
                    })
                    .build();

            ProcessingReport report = runtime.unsafeRunWithHandler(
                    processBatch(List.of()), handler);

            assertEquals(0, report.succeeded().size());
            assertEquals(0, report.failed().size());
            assertEquals(0, summaryCount.get());   // Effect.when(false, …) → skipped
        }

        @Test
        void parallelEnrichment_allItemsConverted() throws Throwable {
            int count = 8;
            List<CsvRow> rows = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                rows.add(new CsvRow(i + 1, "C%03d,1.00,USD".formatted(i)));
            }
            AtomicInteger enrichCount = new AtomicInteger();

            CapabilityHandler<Capability<?>> handler = CapabilityHandler.builder()
                    .on(BatchCapability.ParseRow.class, cap -> {
                        String[] p = cap.row().rawData().split(",");
                        return new ParsedRecord(cap.row().lineNumber(), p[0],
                                Double.parseDouble(p[1]), p[2]);
                    })
                    .on(BatchCapability.ConvertToUsd.class, cap -> {
                        enrichCount.incrementAndGet(); return cap.amount();
                    })
                    .on(BatchCapability.PersistRecord.class, cap -> null)
                    .on(BatchCapability.WriteSummary.class, cap -> null)
                    .build();

            ProcessingReport report = runtime.unsafeRunWithHandler(processBatch(rows), handler);

            assertEquals(count, report.succeeded().size());
            assertEquals(0, report.failed().size());
            assertEquals(count, enrichCount.get());
        }
    }
}
