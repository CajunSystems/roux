# Phase 11, Plan 1: F-Bounded Builder + Integration Tests

## objective
Finalize the F-bounded `CapabilityHandler.Builder.on()` by adding the `& Capability<R>` intersection bound (the user has already added `Builder<F>` generics and `forType()` — this plan adds the final type constraint and verifies no regressions), then write an end-to-end `LayerIntegrationTest` covering the full Layer → Env → EffectWithEnv workflow.

---

## execution_context
- `lib/src/main/java/com/cajunsystems/roux/capability/CapabilityHandler.java` (has uncommitted user changes)
- `docs/CAPABILITIES.md` (has uncommitted user changes)
- `lib/src/test/java/com/cajunsystems/roux/capability/LayerIntegrationTest.java` (to be created)

---

## context

**Current state of `Builder.on()` (user's uncommitted change):**
```java
public <C extends F, R> Builder<F> on(Class<C> type, ThrowingFunction<C, R> handler)
```
`R` is free — any return type. Not yet F-bounded against the capability's declared return type.

**Target `on()` signature:**
```java
public <R, C extends F & Capability<R>> Builder<F> on(Class<C> type, ThrowingFunction<C, R> handler)
```
The intersection `C extends F & Capability<R>` captures `R` from the capability's own type parameter. The compiler now rejects handlers that return the wrong type.

**Impact on existing call sites:**
- `builder()` returns `Builder<Capability<?>>` — for each `.on()` call, `C` is a concrete type like `AppCapability.Log` which extends `Capability<Void>`, so `R` is inferred as `Void`. Handlers returning the right type (or `null`) remain valid.
- `forType(AppCapability.class)` returns `Builder<AppCapability<?>>` — same logic per call.
- Call sites returning `null` for `Capability<Void>` capabilities: still fine (`null` is assignable to `Void`).
- No call site currently returns a wrong type — existing tests all pass with correct return types.

**User's changes to commit:**
The user has already written the `Builder<F>` generic, `forType()`, and the docs. These need to be committed as part of this plan (alongside the F-bound addition).

---

## tasks

### Task 1: Add `& Capability<R>` intersection bound to `Builder.on()`

**File:** `lib/src/main/java/com/cajunsystems/roux/capability/CapabilityHandler.java`

Read the file first. Change the `on()` method signature from:
```java
public <C extends F, R> Builder<F> on(
        Class<C> type,
        ThrowingFunction<C, R> handler
)
```
to:
```java
public <R, C extends F & Capability<R>> Builder<F> on(
        Class<C> type,
        ThrowingFunction<C, R> handler
)
```

No other changes — only the method signature. The body remains identical.

After editing, run: `./gradlew :lib:test` to verify all existing tests still pass.

Commit both `CapabilityHandler.java` AND `docs/CAPABILITIES.md` (both have user's staged changes):
```
git add lib/src/main/java/com/cajunsystems/roux/capability/CapabilityHandler.java
git add docs/CAPABILITIES.md
```
Commit: `feat(11-1): F-bounded Builder.on() with C extends F & Capability<R>`

---

### Task 2: Write `LayerIntegrationTest.java`

**File:** `lib/src/test/java/com/cajunsystems/roux/capability/LayerIntegrationTest.java`

This test demonstrates the full Milestone 2 workflow end-to-end: define capability families, build layers, compose them, build the environment, declare a typed effect, run it.

```java
package com.cajunsystems.roux.capability;

import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.EffectRuntime;
import com.cajunsystems.roux.EffectWithEnv;
import com.cajunsystems.roux.runtime.DefaultEffectRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test for the full Layer → Env → EffectWithEnv workflow.
 *
 * <p>Demonstrates: define capabilities, build leaf layers, compose horizontally
 * with {@code and()}, compose vertically with {@code andProvide()}, build the
 * environment, declare typed effects with {@code EffectWithEnv}, and run them
 * with compile-time environment verification.
 */
class LayerIntegrationTest {

    // -----------------------------------------------------------------------
    // Capability families
    // -----------------------------------------------------------------------

    sealed interface DbOps extends Capability<String> {
        record Query(String sql) implements DbOps {}
        record Execute(String sql) implements DbOps {}
    }

    sealed interface AuditOps extends Capability<String> {
        record Log(String event) implements AuditOps {}
    }

    sealed interface ConfigOps extends Capability<String> {
        record Get(String key) implements ConfigOps {}
    }

    sealed interface EmailOps extends Capability<String> {
        record Send(String to, String subject) implements EmailOps {}
    }

    // -----------------------------------------------------------------------
    // Test state
    // -----------------------------------------------------------------------

    private Map<String, String> database;
    private List<String> auditLog;
    private Map<String, String> config;
    private List<String> sentEmails;
    private EffectRuntime runtime;

    @BeforeEach
    void setUp() {
        database = new HashMap<>();
        auditLog = new ArrayList<>();
        config = new HashMap<>();
        sentEmails = new ArrayList<>();
        runtime = DefaultEffectRuntime.create();

        // Seed data
        database.put("user:42", "Alice");
        config.put("smtp.host", "smtp.example.com");
        config.put("smtp.from", "noreply@example.com");
    }

    @AfterEach
    void tearDown() throws Exception {
        runtime.close();
    }

    // -----------------------------------------------------------------------
    // Layer factories
    // -----------------------------------------------------------------------

    Layer<Empty, RuntimeException, DbOps> dbLayer() {
        return Layer.succeed(DbOps.class, cap -> switch (cap) {
            case DbOps.Query q -> database.getOrDefault(q.sql(), "not-found");
            case DbOps.Execute e -> { database.put(e.sql(), "executed"); yield "ok"; }
        });
    }

    Layer<Empty, RuntimeException, AuditOps> auditLayer() {
        return Layer.succeed(AuditOps.class, cap -> switch (cap) {
            case AuditOps.Log l -> { auditLog.add(l.event()); yield "logged"; }
        });
    }

    Layer<Empty, RuntimeException, ConfigOps> configLayer() {
        return Layer.succeed(ConfigOps.class, cap -> switch (cap) {
            case ConfigOps.Get g -> config.getOrDefault(g.key(), "unset");
        });
    }

    // EmailOps layer depends on ConfigOps — reads smtp host during construction
    Layer<ConfigOps, Exception, EmailOps> emailLayer() {
        return Layer.fromEffect(
                EmailOps.class,
                configEnv -> Effect.suspend(() -> {
                    String host = configEnv.toHandler().handle(new ConfigOps.Get("smtp.host"));
                    String from = configEnv.toHandler().handle(new ConfigOps.Get("smtp.from"));
                    return (EmailOps cap) -> switch (cap) {
                        case EmailOps.Send s -> {
                            sentEmails.add(host + "|" + from + "|" + s.to() + "|" + s.subject());
                            yield "sent";
                        }
                    };
                })
        );
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    /**
     * Scenario 1: Horizontal composition — both layers have same input (Empty),
     * combined env covers With<DbOps, AuditOps>.
     *
     * <p>Typed effect declares both capabilities; run() compiles only with
     * the combined HandlerEnv<With<DbOps, AuditOps>>.
     */
    @Test
    void horizontalComposition_fullWorkflow() throws Throwable {
        // 1. Compose layers horizontally
        Layer<Empty, Throwable, With<DbOps, AuditOps>> appLayer = dbLayer().and(auditLayer());

        // 2. Build environment
        HandlerEnv<With<DbOps, AuditOps>> env =
                runtime.unsafeRun(appLayer.build(HandlerEnv.empty()));

        // 3. Declare a typed effect requiring both capabilities
        EffectWithEnv<With<DbOps, AuditOps>, Throwable, String> fetchAndAudit =
                EffectWithEnv.of(
                        new DbOps.Query("user:42")
                                .toEffect()
                                .flatMap(name ->
                                        new AuditOps.Log("fetched user: " + name)
                                                .toEffect()
                                                .map(__ -> name)
                                )
                );

        // 4. Run — env covers With<DbOps, AuditOps>
        String result = fetchAndAudit.run(env, runtime);

        assertEquals("Alice", result);
        assertEquals(List.of("fetched user: Alice"), auditLog);
    }

    /**
     * Scenario 2: Vertical composition — configLayer output feeds emailLayer input.
     * Combined env covers With<ConfigOps, EmailOps>.
     */
    @Test
    void verticalComposition_configFeedsEmail() throws Throwable {
        // 1. configLayer provides ConfigOps; emailLayer reads config during construction
        Layer<Empty, Throwable, With<ConfigOps, EmailOps>> combined =
                configLayer().andProvide(emailLayer());

        // 2. Build environment
        HandlerEnv<With<ConfigOps, EmailOps>> env =
                runtime.unsafeRun(combined.build(HandlerEnv.empty()));

        // 3. Declare a typed effect that sends an email
        EffectWithEnv<With<ConfigOps, EmailOps>, Throwable, String> sendWelcome =
                EffectWithEnv.of(
                        new EmailOps.Send("alice@example.com", "Welcome!").toEffect()
                );

        // 4. Run
        String result = sendWelcome.run(env, runtime);

        assertEquals("sent", result);
        assertEquals(1, sentEmails.size());
        assertEquals("smtp.example.com|noreply@example.com|alice@example.com|Welcome!", sentEmails.get(0));
    }

    /**
     * Scenario 3: Three capabilities — horizontal composition of three leaf layers.
     */
    @Test
    void threeCapabilities_horizontalComposition() throws Throwable {
        Layer<Empty, Throwable, With<With<DbOps, AuditOps>, ConfigOps>> triLayer =
                dbLayer().and(auditLayer()).and(configLayer());

        HandlerEnv<With<With<DbOps, AuditOps>, ConfigOps>> env =
                runtime.unsafeRun(triLayer.build(HandlerEnv.empty()));

        // All three capabilities dispatch correctly
        String dbResult = env.toHandler().handle(new DbOps.Query("user:42"));
        assertEquals("Alice", dbResult);

        env.toHandler().handle(new AuditOps.Log("accessed Alice"));
        assertEquals(1, auditLog.size());

        String cfgResult = env.toHandler().handle(new ConfigOps.Get("smtp.host"));
        assertEquals("smtp.example.com", cfgResult);
    }

    /**
     * Scenario 4: HandlerEnv.of() with F-bounded on() — compiler enforces return type.
     * HandlerEnv.of() delegates to CapabilityHandler.builder().on() internally.
     */
    @Test
    void handlerEnvOf_usesCorrectReturnType() throws Throwable {
        // DbOps extends Capability<String> — handler must return String
        HandlerEnv<DbOps> env = HandlerEnv.of(DbOps.class, cap -> switch (cap) {
            case DbOps.Query q -> "query-result";
            case DbOps.Execute e -> "execute-result";
        });

        String result = env.toHandler().handle(new DbOps.Query("anything"));
        assertEquals("query-result", result);
    }

    /**
     * Scenario 5: pure EffectWithEnv (no capabilities) runs against empty env.
     */
    @Test
    void pureEffect_runsWithEmptyEnv() throws Exception {
        EffectWithEnv<Empty, RuntimeException, Integer> constant =
                EffectWithEnv.pure(Effect.succeed(42));

        int result = constant.run(HandlerEnv.empty(), runtime);
        assertEquals(42, result);
    }
}
```

Run: `./gradlew :lib:test --tests "com.cajunsystems.roux.capability.LayerIntegrationTest"`

Then full suite: `./gradlew :lib:test`

Commit: `test(11-1): LayerIntegrationTest end-to-end Layer → Env → EffectWithEnv`
Stage only: `lib/src/test/java/com/cajunsystems/roux/capability/LayerIntegrationTest.java`

---

## verification

```bash
./gradlew :lib:test
```

All tests green — no regressions.

---

## success_criteria
- [ ] `Builder.on()` has intersection bound `C extends F & Capability<R>`
- [ ] `./gradlew :lib:test` — all tests pass (no regressions)
- [ ] `LayerIntegrationTest` created with 5 end-to-end tests covering horizontal composition, vertical composition, three-capability nesting, HandlerEnv.of(), and pure effects
- [ ] User's `forType()` and docs changes are committed

---

## output
- Modified: `lib/src/main/java/com/cajunsystems/roux/capability/CapabilityHandler.java`
- Modified: `docs/CAPABILITIES.md`
- Created: `lib/src/test/java/com/cajunsystems/roux/capability/LayerIntegrationTest.java`
