# Example: Batch Processing Pipeline

Processing a list of records — with parallel execution, sequential fallbacks,
conditional steps, and structured error handling — expressed as composable
`Effect` operations.

---

## Domain Model

```java
record CsvRow(int lineNumber, String rawData) {}
record ParsedRecord(int lineNumber, String customerId, double amount, String currency) {}
record EnrichedRecord(ParsedRecord record, double amountUsd) {}
record ProcessingReport(List<EnrichedRecord> succeeded, List<String> failed) {}
```

---

## Capability Definitions

```java
sealed interface BatchCapability<R> extends Capability<R> {

    /** Validate and parse a raw CSV row. */
    record ParseRow(CsvRow row) implements BatchCapability<ParsedRecord> {}

    /** Convert an amount to USD using a cached FX rate. */
    record ConvertToUsd(double amount, String currency)
            implements BatchCapability<Double> {}

    /** Persist a single enriched record. */
    record PersistRecord(EnrichedRecord record)
            implements BatchCapability<Void> {}

    /** Write a summary report for the whole batch. */
    record WriteSummary(ProcessingReport report)
            implements BatchCapability<Void> {}
}
```

---

## The Pipeline

### Step 1 — Parse and validate each row (sequential, short-circuits on first fatal error)

`Effects.traverse` maps each element to an effect, then runs them
**sequentially**. If any effect fails, the whole traverse fails immediately.

```java
import static com.cajunsystems.roux.Effects.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Parse all rows, collecting validation errors without stopping the batch.
 *
 * We use `.attempt()` to convert each parse failure into an Either so that
 * one bad row doesn't abort the whole batch.
 */
Effect<Throwable, List<Either<String, ParsedRecord>>> parseAll(List<CsvRow> rows) {
    return traverse(rows, row ->
            new BatchCapability.ParseRow(row)
                    .<Throwable, ParsedRecord>toEffect()
                    .attempt()                          // Either<Throwable, ParsedRecord>
                    .map(result -> result
                            .mapLeft(err -> "line " + row.lineNumber() + ": " + err.getMessage()))
    );
}
```

### Step 2 — Enrich valid records in parallel

`Effects.parAll` runs all effects **concurrently** and collects results in
order. Each FX lookup is independent, so we can fire them all at once.

```java
/**
 * Convert each valid record to USD in parallel.
 * If any FX lookup fails, the entire parallel batch fails.
 */
Effect<Throwable, List<EnrichedRecord>> enrichParallel(List<ParsedRecord> records) {
    List<Effect<Throwable, EnrichedRecord>> enrichEffects = records.stream()
            .map(record ->
                    new BatchCapability.ConvertToUsd(record.amount(), record.currency())
                            .<Throwable, Double>toEffect()
                            .map(usd -> new EnrichedRecord(record, usd)))
            .toList();

    return parAll(enrichEffects);
}
```

### Step 3 — Persist each enriched record sequentially

`Effects.sequence` runs a list of effects one after another, collecting all
results. Use it when order matters or the target cannot handle concurrent writes.

```java
/**
 * Write enriched records to the database one at a time.
 * sequence preserves insertion order and propagates any write failure.
 */
Effect<Throwable, Void> persistAll(List<EnrichedRecord> enriched) {
    List<Effect<Throwable, Void>> persistEffects = enriched.stream()
            .map(record -> new BatchCapability.PersistRecord(record)
                    .<Throwable, Void>toEffect())
            .toList();

    // sequence returns List<Void>; we discard it
    return sequence(persistEffects).map(__ -> null);
}
```

### Step 4 — Full pipeline with conditional summary

`Effect.when` / `Effect.unless` gate effects on a boolean. Here we skip the
summary step when the batch is empty, avoiding a pointless write.

```java
/**
 * End-to-end batch pipeline:
 *
 *   1. Parse rows (sequential, errors collected via Either)
 *   2. Separate successes from failures
 *   3. Enrich successes in parallel
 *   4. Persist enriched records sequentially
 *   5. Write a summary report — but only if there is something to report
 */
public static Effect<Throwable, ProcessingReport> processBatch(List<CsvRow> rows) {
    return parseAll(rows)
            .flatMap(parseResults -> {

                // Partition successes and failures
                var succeeded = parseResults.stream()
                        .filter(Either::isRight)
                        .map(e -> e.getOrElse((ParsedRecord) null))
                        .toList();
                var failed = parseResults.stream()
                        .filter(Either::isLeft)
                        .map(e -> e.fold(msg -> msg, __ -> ""))
                        .toList();

                // Enrich and persist valid records
                Effect<Throwable, List<EnrichedRecord>> enrichAndPersist =
                        enrichParallel(succeeded)
                                .flatMap(enriched ->
                                        persistAll(enriched).map(__ -> enriched));

                // If there are no valid records, skip enrichment entirely
                Effect<Throwable, List<EnrichedRecord>> maybEnrich = succeeded.isEmpty()
                        ? Effect.succeed(List.of())
                        : enrichAndPersist;

                return maybEnrich.flatMap(enriched -> {
                    ProcessingReport report =
                            new ProcessingReport(enriched, failed);

                    // Only write the summary when there is at least one outcome
                    boolean hasSomething = !enriched.isEmpty() || !failed.isEmpty();
                    Effect<Throwable, Void> summary =
                            Effect.when(hasSomething,
                                    new BatchCapability.WriteSummary(report)
                                            .<Throwable, Void>toEffect());

                    return summary.map(__ -> report);
                });
            });
}
```

---

## Test Handler

```java
@Test
void processBatch_mixedRows() throws Throwable {
    List<CsvRow> rows = List.of(
            new CsvRow(1, "C001,50.00,USD"),
            new CsvRow(2, "INVALID_DATA"),         // will fail parsing
            new CsvRow(3, "C002,30.00,EUR")
    );

    // Test-double state
    List<EnrichedRecord> persisted = new ArrayList<>();
    List<ProcessingReport> reports = new ArrayList<>();

    CapabilityHandler<Capability<?>> handler = CapabilityHandler.builder()

        .on(BatchCapability.ParseRow.class, cap -> {
            String[] parts = cap.row().rawData().split(",");
            if (parts.length != 3) {
                throw new IllegalArgumentException("bad format");
            }
            return new ParsedRecord(
                    cap.row().lineNumber(),
                    parts[0],
                    Double.parseDouble(parts[1]),
                    parts[2]
            );
        })

        .on(BatchCapability.ConvertToUsd.class, cap ->
            switch (cap.currency()) {
                case "USD" -> cap.amount();
                case "EUR" -> cap.amount() * 1.08;
                default    -> throw new IllegalArgumentException("unknown currency: " + cap.currency());
            }
        )

        .on(BatchCapability.PersistRecord.class, cap -> {
            persisted.add(cap.record());
            return null;
        })

        .on(BatchCapability.WriteSummary.class, cap -> {
            reports.add(cap.report());
            return null;
        })

        .build();

    try (var runtime = DefaultEffectRuntime.create()) {
        ProcessingReport report = runtime.unsafeRunWithHandler(
                processBatch(rows),
                handler
        );

        // Two rows parsed successfully, one failed
        assertEquals(2, report.succeeded().size());
        assertEquals(1, report.failed().size());
        assertTrue(report.failed().get(0).contains("line 2"));

        // Both valid records were persisted
        assertEquals(2, persisted.size());

        // Summary was written (non-empty batch)
        assertEquals(1, reports.size());

        // EUR amount was converted correctly
        EnrichedRecord eurRecord = report.succeeded().stream()
                .filter(r -> "C002".equals(r.record().customerId()))
                .findFirst()
                .orElseThrow();
        assertEquals(30.0 * 1.08, eurRecord.amountUsd(), 1e-9);
    }
}

@Test
void processBatch_allInvalid_noEnrichOrPersist() throws Throwable {
    List<CsvRow> rows = List.of(
            new CsvRow(1, "BAD"),
            new CsvRow(2, "ALSO_BAD")
    );

    AtomicInteger enrichCalls  = new AtomicInteger();
    AtomicInteger persistCalls = new AtomicInteger();
    AtomicInteger summaryCalls = new AtomicInteger();

    CapabilityHandler<Capability<?>> handler = CapabilityHandler.builder()
        .on(BatchCapability.ParseRow.class, cap -> {
            throw new IllegalArgumentException("parse error");
        })
        .on(BatchCapability.ConvertToUsd.class, cap -> {
            enrichCalls.incrementAndGet();
            return cap.amount();
        })
        .on(BatchCapability.PersistRecord.class, cap -> {
            persistCalls.incrementAndGet();
            return null;
        })
        .on(BatchCapability.WriteSummary.class, cap -> {
            summaryCalls.incrementAndGet();
            return null;
        })
        .build();

    try (var runtime = DefaultEffectRuntime.create()) {
        ProcessingReport report = runtime.unsafeRunWithHandler(
                processBatch(rows),
                handler
        );

        assertEquals(0, report.succeeded().size());
        assertEquals(2, report.failed().size());

        // Enrich and persist must not have run
        assertEquals(0, enrichCalls.get());
        assertEquals(0, persistCalls.get());

        // Summary still written because there are failures to report
        assertEquals(1, summaryCalls.get());
    }
}

@Test
void processBatch_emptyInput_noOps() throws Throwable {
    AtomicInteger summaryCalls = new AtomicInteger();

    CapabilityHandler<Capability<?>> handler = CapabilityHandler.builder()
        .on(BatchCapability.WriteSummary.class, cap -> {
            summaryCalls.incrementAndGet();
            return null;
        })
        .build();

    try (var runtime = DefaultEffectRuntime.create()) {
        ProcessingReport report = runtime.unsafeRunWithHandler(
                processBatch(List.of()),
                handler
        );

        assertEquals(0, report.succeeded().size());
        assertEquals(0, report.failed().size());

        // when(false, ...) — no summary written for an empty batch
        assertEquals(0, summaryCalls.get());
    }
}
```

---

## Key Patterns Demonstrated

| Pattern | API | When to use |
|---------|-----|-------------|
| Sequential map-to-effect | `Effects.traverse(list, fn)` | Order matters; fail-fast on first error |
| Error collection without abort | `.attempt()` + `Either.mapLeft()` | Gather all failures before deciding |
| Parallel independent effects | `Effects.parAll(list)` | Items are independent; latency matters |
| Sequential writes | `Effects.sequence(list)` | Ordering or concurrency constraints |
| Conditional step | `Effect.when(bool, effect)` | Skip unnecessary work |
| Conditional skip | `Effect.unless(bool, effect)` | Guard that an invariant holds |
| Partition results | `Either.isLeft()` / `Either.isRight()` | Split successes and failures cleanly |
