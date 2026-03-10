package com.cajunsystems.roux;

import com.cajunsystems.roux.capability.*;
import com.cajunsystems.roux.data.Unit;
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
 * Demonstrates and verifies the type-safe handler / layer system for roux.
 *
 * <p>The capability system is extended with three new abstractions:
 * <ul>
 *   <li>{@link HandlerEnv}{@code <R>} — a typed environment backed by a
 *       {@link CapabilityHandler} whose phantom type {@code R} tracks which
 *       capabilities it satisfies.</li>
 *   <li>{@link Layer}{@code <RIn, E, ROut>} — a recipe for building a
 *       {@code HandlerEnv<ROut>} from a {@code HandlerEnv<RIn>}, the direct
 *       analog of {@code ZLayer[RIn, E, ROut]} from ZIO.</li>
 *   <li>{@link TypedEffect}{@code <R, E, A>} — a thin wrapper around
 *       {@code Effect<E, A>} that statically declares the capability requirement
 *       {@code R}.  {@link TypedEffect#run} only compiles when the caller holds a
 *       {@code HandlerEnv<R>} whose phantom type matches.</li>
 * </ul>
 *
 * <p>The phantom types {@link Empty} and {@link With}{@code <A, B>} form a type-level
 * set of capabilities, allowing the compiler to enforce full coverage.
 */
class TypeSafeHandlerTest {

    // -----------------------------------------------------------------------
    // Capability definitions (three independent "services")
    // -----------------------------------------------------------------------

    /** Key-value store operations */
    sealed interface StoreOps extends Capability<String> {
        record Get(String key) implements StoreOps {}
        record Put(String key, String value) implements StoreOps {}
    }

    /** Structured logging operations */
    sealed interface LogOps extends Capability<Unit> {
        record Info(String message) implements LogOps {}
        record Error(String message) implements LogOps {}
    }

    /** Configuration read operations — used to show layer dependencies */
    sealed interface ConfigOps extends Capability<String> {
        record GetConfig(String name) implements ConfigOps {}
    }

    // -----------------------------------------------------------------------
    // Runtime
    // -----------------------------------------------------------------------

    private DefaultEffectRuntime runtime;

    @BeforeEach
    void setUp() {
        runtime = DefaultEffectRuntime.create();
    }

    @AfterEach
    void tearDown() throws Exception {
        runtime.close();
    }

    // -----------------------------------------------------------------------
    // HandlerEnv — single capability
    // -----------------------------------------------------------------------

    @Test
    void singleCapabilityEnvHandlesItsCapability() throws Throwable {
        Map<String, String> store = new HashMap<>();
        HandlerEnv<StoreOps> storeEnv = HandlerEnv.of(StoreOps.class, cap -> switch (cap) {
            case StoreOps.Get g -> store.getOrDefault(g.key(), "missing");
            case StoreOps.Put p -> { store.put(p.key(), p.value()); yield "ok"; }
        });

        // Run directly via the underlying handler — sanity-check the delegation
        storeEnv.toHandler().handle(new StoreOps.Put("name", "Alice"));
        String result = storeEnv.toHandler().handle(new StoreOps.Get("name"));
        assertEquals("Alice", result);
    }

    @Test
    void singleCapabilityEnvRejectsUnregisteredCapability() {
        HandlerEnv<StoreOps> storeEnv = HandlerEnv.of(StoreOps.class, cap -> "ok");

        assertThrows(UnsupportedOperationException.class,
                () -> storeEnv.toHandler().handle(new LogOps.Info("oops")));
    }

    // -----------------------------------------------------------------------
    // HandlerEnv — combining two environments with and()
    // -----------------------------------------------------------------------

    @Test
    void combinedEnvHandlesBothCapabilityFamilies() throws Throwable {
        List<String> logSink = new ArrayList<>();
        Map<String, String> store = new HashMap<>();

        HandlerEnv<StoreOps> storeEnv = HandlerEnv.of(StoreOps.class, cap -> switch (cap) {
            case StoreOps.Get g -> store.getOrDefault(g.key(), "missing");
            case StoreOps.Put p -> { store.put(p.key(), p.value()); yield "ok"; }
        });

        HandlerEnv<LogOps> logEnv = HandlerEnv.of(LogOps.class, cap -> switch (cap) {
            case LogOps.Info  m -> { logSink.add("[INFO]  " + m.message());  yield Unit.unit(); }
            case LogOps.Error m -> { logSink.add("[ERROR] " + m.message()); yield Unit.unit(); }
        });

        // Combine: the type system now tracks both StoreOps and LogOps
        HandlerEnv<With<StoreOps, LogOps>> fullEnv = storeEnv.and(logEnv);
        CapabilityHandler<Capability<?>> handler = fullEnv.toHandler();

        handler.handle(new StoreOps.Put("greeting", "hello"));
        handler.handle(new LogOps.Info("stored greeting"));
        String value = handler.handle(new StoreOps.Get("greeting"));

        assertEquals("hello", value);
        assertEquals(List.of("[INFO]  stored greeting"), logSink);
    }

    @Test
    void andIsAssociativeAtRuntime() throws Throwable {
        List<String> logSink = new ArrayList<>();
        Map<String, String> cfg = Map.of("host", "localhost");

        HandlerEnv<StoreOps>  storeEnv  = HandlerEnv.of(StoreOps.class,  c -> "store");
        HandlerEnv<LogOps>    logEnv    = HandlerEnv.of(LogOps.class,    c -> Unit.unit());
        HandlerEnv<ConfigOps> configEnv = HandlerEnv.of(ConfigOps.class, c -> switch (c) {
            case ConfigOps.GetConfig gc -> cfg.getOrDefault(gc.name(), "?");
        });

        // Left-nested: With<With<StoreOps,LogOps>, ConfigOps>
        HandlerEnv<With<With<StoreOps, LogOps>, ConfigOps>> leftNested =
                storeEnv.and(logEnv).and(configEnv);

        // Right-nested: With<StoreOps, With<LogOps, ConfigOps>>
        HandlerEnv<With<StoreOps, With<LogOps, ConfigOps>>> rightNested =
                storeEnv.and(logEnv.and(configEnv));

        // Both should route correctly at runtime
        assertEquals("localhost",
                (String) leftNested.toHandler().handle(new ConfigOps.GetConfig("host")));
        assertEquals("localhost",
                (String) rightNested.toHandler().handle(new ConfigOps.GetConfig("host")));
    }

    // -----------------------------------------------------------------------
    // TypedEffect — declaration and execution
    // -----------------------------------------------------------------------

    @Test
    void typedEffectRunsWithMatchingEnv() throws Throwable {
        Map<String, String> store = new HashMap<>();
        List<String> log = new ArrayList<>();

        // Declare a typed effect that requires both StoreOps and LogOps
        TypedEffect<With<StoreOps, LogOps>, Throwable, String> effect = TypedEffect.of(
                new StoreOps.Put("counter", "42")
                        .toEffect()
                        .flatMap(__ -> new LogOps.Info("stored counter").toEffect())
                        .flatMap(__ -> new StoreOps.Get("counter").toEffect())
        );

        HandlerEnv<StoreOps> storeEnv = HandlerEnv.of(StoreOps.class, cap -> switch (cap) {
            case StoreOps.Get g -> store.getOrDefault(g.key(), "missing");
            case StoreOps.Put p -> { store.put(p.key(), p.value()); yield "ok"; }
        });

        HandlerEnv<LogOps> logEnv = HandlerEnv.of(LogOps.class, cap -> switch (cap) {
            case LogOps.Info m  -> { log.add(m.message()); yield Unit.unit(); }
            case LogOps.Error m -> { log.add(m.message()); yield Unit.unit(); }
        });

        // run() only compiles because storeEnv.and(logEnv) is typed as
        // HandlerEnv<With<StoreOps, LogOps>> — matching the effect's R parameter
        String result = effect.run(storeEnv.and(logEnv), runtime);

        assertEquals("42", result);
        assertEquals(List.of("stored counter"), log);
    }

    @Test
    void typedEffectCombinatorsPreserveRequirement() throws Throwable {
        HandlerEnv<StoreOps> env = HandlerEnv.of(StoreOps.class, cap -> switch (cap) {
            case StoreOps.Get g -> "value";
            case StoreOps.Put p -> "ok";
        });

        // map — R is preserved
        TypedEffect<StoreOps, Throwable, Integer> lengthEffect =
                TypedEffect.<StoreOps, Throwable, String>of(
                        new StoreOps.Get("x").toEffect()
                ).map(String::length);

        assertEquals(5, (int) lengthEffect.run(env, runtime));

        // flatMap — R is preserved
        TypedEffect<StoreOps, Throwable, String> chained =
                TypedEffect.<StoreOps, Throwable, String>of(
                        new StoreOps.Get("x").toEffect()
                ).flatMap(v ->
                        TypedEffect.of(new StoreOps.Get(v).toEffect())
                );

        // "value" → look up "value" → "value"
        assertEquals("value", chained.run(env, runtime));
    }

    @Test
    void pureTypedEffectRequiresNoCapabilities() throws Throwable {
        TypedEffect<Empty, RuntimeException, Integer> pureEffect =
                TypedEffect.pure(Effect.succeed(42));

        // HandlerEnv.empty() satisfies Empty — compiles and runs
        Integer result = pureEffect.run(HandlerEnv.empty(), runtime);
        assertEquals(42, result);
    }

    // -----------------------------------------------------------------------
    // Layer — horizontal composition (and)
    // -----------------------------------------------------------------------

    @Test
    void layerSucceedBuildsHandlerFromLambda() throws Throwable {
        Map<String, String> store = new HashMap<>();

        Layer<Empty, RuntimeException, StoreOps> storeLayer =
                Layer.succeed(StoreOps.class, cap -> switch (cap) {
                    case StoreOps.Get g -> store.getOrDefault(g.key(), "?");
                    case StoreOps.Put p -> { store.put(p.key(), p.value()); yield "ok"; }
                });

        HandlerEnv<StoreOps> env = runtime.unsafeRun(storeLayer.build(HandlerEnv.empty()));
        env.toHandler().handle(new StoreOps.Put("k", "v"));
        assertEquals("v", (String) env.toHandler().handle(new StoreOps.Get("k")));
    }

    @Test
    void horizontalLayerCompositionProvidesBothCapabilities() throws Throwable {
        List<String> log = new ArrayList<>();
        Map<String, String> store = new HashMap<>();

        Layer<Empty, RuntimeException, StoreOps> storeLayer =
                Layer.succeed(StoreOps.class, cap -> switch (cap) {
                    case StoreOps.Get g -> store.getOrDefault(g.key(), "?");
                    case StoreOps.Put p -> { store.put(p.key(), p.value()); yield "ok"; }
                });

        Layer<Empty, RuntimeException, LogOps> logLayer =
                Layer.succeed(LogOps.class, cap -> switch (cap) {
                    case LogOps.Info  m -> { log.add("INFO:"  + m.message()); yield Unit.unit(); }
                    case LogOps.Error m -> { log.add("ERROR:" + m.message()); yield Unit.unit(); }
                });

        // ++ operator: same input (Empty), merged output (With<StoreOps, LogOps>)
        Layer<Empty, Throwable, With<StoreOps, LogOps>> appLayer = storeLayer.and(logLayer);
        HandlerEnv<With<StoreOps, LogOps>> env =
                runtime.unsafeRun(appLayer.build(HandlerEnv.empty()));

        env.toHandler().handle(new StoreOps.Put("x", "1"));
        env.toHandler().handle(new LogOps.Info("wrote x"));

        assertEquals("1", (String) env.toHandler().handle(new StoreOps.Get("x")));
        assertEquals(List.of("INFO:wrote x"), log);
    }

    // -----------------------------------------------------------------------
    // Layer — vertical composition (andProvide / >>>)
    // -----------------------------------------------------------------------

    @Test
    void verticalLayerCompositionFeedsOutputAsInput() throws Throwable {
        /*
         * ConfigOps layer has no dependencies.
         * StoreOps layer reads its initial data from ConfigOps during construction.
         *
         * configLayer : Empty      → ConfigOps
         * storeLayer  : ConfigOps  → StoreOps
         * combined    : Empty      → With<ConfigOps, StoreOps>
         */
        Map<String, String> cfg = new HashMap<>(Map.of("seed_key", "seed_value"));

        Layer<Empty, RuntimeException, ConfigOps> configLayer =
                Layer.succeed(ConfigOps.class, cap -> switch (cap) {
                    case ConfigOps.GetConfig gc -> cfg.getOrDefault(gc.name(), "unknown");
                });

        // StoreOps layer uses ConfigOps to pre-populate the store
        Layer<ConfigOps, Exception, StoreOps> storeLayer = Layer.fromEffect(
                StoreOps.class,
                configEnv -> Effect.suspend(() -> {
                    // Read a seed value from config during layer construction
                    String seedValue = configEnv.toHandler().handle(
                            new ConfigOps.GetConfig("seed_key"));

                    Map<String, String> store = new HashMap<>();
                    store.put("seed", seedValue);  // pre-populate

                    return (StoreOps cap) -> switch (cap) {
                        case StoreOps.Get g -> store.getOrDefault(g.key(), "?");
                        case StoreOps.Put p -> { store.put(p.key(), p.value()); yield "ok"; }
                    };
                })
        );

        // >>> operator: configLayer provides what storeLayer needs
        Layer<Empty, Throwable, With<ConfigOps, StoreOps>> combined =
                configLayer.andProvide(storeLayer);

        HandlerEnv<With<ConfigOps, StoreOps>> env =
                runtime.unsafeRun(combined.build(HandlerEnv.empty()));

        // The seed value was injected from config during layer construction
        String seedResult = env.toHandler().handle(new StoreOps.Get("seed"));
        assertEquals("seed_value", seedResult);

        // Config is also available in the combined env
        String configResult = env.toHandler().handle(new ConfigOps.GetConfig("seed_key"));
        assertEquals("seed_value", configResult);
    }

    // -----------------------------------------------------------------------
    // Full integration: Layer → HandlerEnv → TypedEffect
    // -----------------------------------------------------------------------

    @Test
    void endToEndLayerToTypedEffectIntegration() throws Throwable {
        /*
         * A realistic scenario:
         *  1. Build the environment via composed layers.
         *  2. Run a TypedEffect that requires both StoreOps and LogOps.
         *  3. Verify the result and side effects.
         */
        List<String> logSink = new ArrayList<>();
        Map<String, String> storeSink = new HashMap<>();

        Layer<Empty, RuntimeException, StoreOps> storeLayer =
                Layer.succeed(StoreOps.class, cap -> switch (cap) {
                    case StoreOps.Get g -> storeSink.getOrDefault(g.key(), "missing");
                    case StoreOps.Put p -> { storeSink.put(p.key(), p.value()); yield "ok"; }
                });

        Layer<Empty, RuntimeException, LogOps> logLayer =
                Layer.succeed(LogOps.class, cap -> switch (cap) {
                    case LogOps.Info  m -> { logSink.add(m.message()); yield Unit.unit(); }
                    case LogOps.Error m -> { logSink.add(m.message()); yield Unit.unit(); }
                });

        // Build the environment — types are fully tracked
        HandlerEnv<With<StoreOps, LogOps>> env = runtime.unsafeRun(
                storeLayer.and(logLayer).build(HandlerEnv.empty())
        );

        // Declare a typed effect: R = With<StoreOps, LogOps>
        TypedEffect<With<StoreOps, LogOps>, Throwable, String> processUser = TypedEffect.of(
                new StoreOps.Put("user:1", "Alice")
                        .toEffect()
                        .flatMap(__ -> new LogOps.Info("Created user Alice").toEffect())
                        .flatMap(__ -> new StoreOps.Get("user:1").toEffect())
                        .tap(name -> logSink.add("Fetched: " + name))
        );

        // run() enforces that env covers With<StoreOps, LogOps>
        String name = processUser.run(env, runtime);

        assertEquals("Alice", name);
        assertEquals(List.of("Created user Alice", "Fetched: Alice"), logSink);
        assertEquals("Alice", storeSink.get("user:1"));
    }

    // -----------------------------------------------------------------------
    // Interop: fromHandler escape hatch
    // -----------------------------------------------------------------------

    @Test
    void fromHandlerInteropsWithExistingCapabilityHandler() throws Throwable {
        // Pre-built handler using the existing builder API
        CapabilityHandler<Capability<?>> legacyHandler = CapabilityHandler.builder()
                .on(StoreOps.Get.class, c -> "legacy-" + c.key())
                .on(StoreOps.Put.class, c -> "ok")
                .build();

        // Wrap it into the typed system via fromHandler (escape hatch)
        // The caller asserts the phantom type R = StoreOps
        HandlerEnv<StoreOps> env = HandlerEnv.fromHandler(legacyHandler);

        TypedEffect<StoreOps, Throwable, String> effect =
                TypedEffect.of(new StoreOps.Get("hello").toEffect());

        assertEquals("legacy-hello", effect.run(env, runtime));
    }

    @Test
    void layerFromHandlerWrapsExistingHandler() throws Throwable {
        // CapabilityHandler.handle has a generic return type, so anonymous class is required.
        // Here we build one via the builder API (which returns the wide type) and wrap it.
        CapabilityHandler<Capability<?>> legacyLogHandler = CapabilityHandler.builder()
                .on(LogOps.Info.class,  m -> Unit.unit())
                .on(LogOps.Error.class, m -> Unit.unit())
                .build();

        // fromHandler wraps the wide handler; phantom type LogOps is asserted by the caller
        HandlerEnv<LogOps> env = HandlerEnv.fromHandler(legacyLogHandler);

        // No exception → handler dispatched successfully
        assertDoesNotThrow(() -> env.toHandler().handle(new LogOps.Info("hello")));
    }
}
