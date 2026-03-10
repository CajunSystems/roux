package com.cajunsystems.roux.capability;

import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.data.ThrowingFunction;
import com.cajunsystems.roux.data.Unit;
import com.cajunsystems.roux.runtime.DefaultEffectRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LayerTest {

    // -----------------------------------------------------------------------
    // Test capability families
    // -----------------------------------------------------------------------

    sealed interface StoreOps extends Capability<String> {
        record Get(String key)               implements StoreOps {}
        record Put(String key, String value) implements StoreOps {}
    }

    sealed interface ConfigOps extends Capability<String> {
        record Get(String key) implements ConfigOps {}
    }

    sealed interface LogOps extends Capability<Unit> {
        record Info(String message) implements LogOps {}
    }

    private DefaultEffectRuntime runtime;

    @BeforeEach
    void setUp() {
        runtime = DefaultEffectRuntime.create();
    }

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    // -----------------------------------------------------------------------
    // Layer.succeed() — leaf layer, no dependencies
    // -----------------------------------------------------------------------

    @Test
    void succeed_buildReturnsHandlerEnv() throws Throwable {
        Map<String, String> store = new HashMap<>();
        store.put("greeting", "hello");

        Layer<Empty, RuntimeException, StoreOps> layer = Layer.succeed(StoreOps.class, cap -> switch (cap) {
            case StoreOps.Get g -> store.getOrDefault(g.key(), "missing");
            case StoreOps.Put p -> { store.put(p.key(), p.value()); yield "ok"; }
        });

        // build() returns Effect<RuntimeException, HandlerEnv<StoreOps>>
        HandlerEnv<StoreOps> env = runtime.unsafeRun(layer.build(HandlerEnv.empty()));

        String result = env.toHandler().handle(new StoreOps.Get("greeting"));
        assertEquals("hello", result);
    }

    @Test
    void succeed_buildPutDispatchesCorrectly() throws Throwable {
        Map<String, String> store = new HashMap<>();

        Layer<Empty, RuntimeException, StoreOps> layer = Layer.succeed(StoreOps.class, cap -> switch (cap) {
            case StoreOps.Get g -> store.getOrDefault(g.key(), "missing");
            case StoreOps.Put p -> { store.put(p.key(), p.value()); yield "ok"; }
        });

        HandlerEnv<StoreOps> env = runtime.unsafeRun(layer.build(HandlerEnv.empty()));
        env.toHandler().handle(new StoreOps.Put("k", "v"));

        assertEquals("v", store.get("k"));
    }

    @Test
    void succeed_buildIsIdempotent() throws Throwable {
        // Calling build() twice produces independent HandlerEnvs backed by the same handler
        Layer<Empty, RuntimeException, StoreOps> layer = Layer.succeed(StoreOps.class, cap -> "constant");

        HandlerEnv<StoreOps> env1 = runtime.unsafeRun(layer.build(HandlerEnv.empty()));
        HandlerEnv<StoreOps> env2 = runtime.unsafeRun(layer.build(HandlerEnv.empty()));

        assertEquals("constant", env1.toHandler().handle(new StoreOps.Get("x")));
        assertEquals("constant", env2.toHandler().handle(new StoreOps.Get("y")));
    }

    // -----------------------------------------------------------------------
    // Layer.fromEffect() — effectful handler construction
    // -----------------------------------------------------------------------

    @Test
    void fromEffect_buildsHandlerFromSucceedEffect() throws Throwable {
        // fromEffect where the effect is already determined (no input env needed)
        Layer<Empty, Throwable, StoreOps> layer = Layer.fromEffect(
                StoreOps.class,
                env -> {
                    ThrowingFunction<StoreOps, String> handler = cap -> switch (cap) {
                        case StoreOps.Get g -> "computed-" + g.key();
                        case StoreOps.Put p -> "ok";
                    };
                    return Effect.succeed(handler);
                }
        );

        HandlerEnv<StoreOps> env = runtime.unsafeRun(layer.build(HandlerEnv.empty()));
        String result = env.toHandler().handle(new StoreOps.Get("name"));
        assertEquals("computed-name", result);
    }

    @Test
    void fromEffect_canReadFromInputEnv() throws Throwable {
        // ConfigOps provides a prefix string used to configure StoreOps
        Map<String, String> config = new HashMap<>();
        config.put("prefix", "v2");

        HandlerEnv<ConfigOps> configEnv = HandlerEnv.of(ConfigOps.class, cap -> switch (cap) {
            case ConfigOps.Get g -> config.getOrDefault(g.key(), "default");
        });

        // StoreOps layer reads prefix from ConfigOps during construction
        Layer<ConfigOps, Throwable, StoreOps> storeLayer = Layer.fromEffect(
                StoreOps.class,
                env -> Effect.suspend(() -> {
                    String prefix = env.toHandler().handle(new ConfigOps.Get("prefix"));
                    ThrowingFunction<StoreOps, String> handler = cap -> switch (cap) {
                        case StoreOps.Get g -> prefix + "-" + g.key();
                        case StoreOps.Put p -> "ok";
                    };
                    return handler;
                })
        );

        HandlerEnv<StoreOps> storeEnv = runtime.unsafeRun(storeLayer.build(configEnv));
        String result = storeEnv.toHandler().handle(new StoreOps.Get("item"));
        assertEquals("v2-item", result);
    }

    @Test
    void fromEffect_propagatesFailure() {
        // If the constructing effect fails, build() fails too
        Layer<Empty, Exception, StoreOps> failingLayer = Layer.fromEffect(
                StoreOps.class,
                env -> Effect.fail(new Exception("construction failed"))
        );

        assertThrows(Exception.class, () ->
                runtime.unsafeRun(failingLayer.build(HandlerEnv.empty())));
    }

    // -----------------------------------------------------------------------
    // build() — functional interface directly
    // -----------------------------------------------------------------------

    @Test
    void layer_isAFunctionalInterface_lambdaWorks() throws Throwable {
        // Layer is a @FunctionalInterface — can be expressed as a lambda
        Layer<Empty, RuntimeException, LogOps> logLayer =
                env -> Effect.succeed(HandlerEnv.of(LogOps.class, cap -> Unit.unit()));

        HandlerEnv<LogOps> env = runtime.unsafeRun(logLayer.build(HandlerEnv.empty()));
        assertNotNull(env);
        // Just verify it dispatches without throwing
        env.toHandler().handle(new LogOps.Info("test"));
    }
}
