package com.cajunsystems.roux;

import com.cajunsystems.roux.capability.Capability;
import com.cajunsystems.roux.capability.Empty;
import com.cajunsystems.roux.capability.HandlerEnv;
import com.cajunsystems.roux.capability.With;
import com.cajunsystems.roux.data.Unit;
import com.cajunsystems.roux.runtime.DefaultEffectRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EffectWithEnvTest {

    // -----------------------------------------------------------------------
    // Test capability families (class-level — sealed interfaces cannot be local)
    // -----------------------------------------------------------------------

    sealed interface StoreOps extends Capability<String> {
        record Get(String key)               implements StoreOps {}
        record Put(String key, String value) implements StoreOps {}
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
    // EffectWithEnv.pure() — Empty requirements
    // -----------------------------------------------------------------------

    @Test
    void pure_runsWithEmptyEnv() throws Throwable {
        EffectWithEnv<Empty, RuntimeException, Integer> effect =
                EffectWithEnv.pure(Effect.succeed(42));

        Integer result = effect.run(HandlerEnv.empty(), runtime);

        assertEquals(42, result);
    }

    @Test
    void pure_failingEffect_propagatesError() {
        EffectWithEnv<Empty, RuntimeException, Integer> effect =
                EffectWithEnv.pure(Effect.fail(new RuntimeException("boom")));

        assertThrows(RuntimeException.class, () ->
                effect.run(HandlerEnv.empty(), runtime));
    }

    // -----------------------------------------------------------------------
    // EffectWithEnv.of() — declared capability requirements
    // -----------------------------------------------------------------------

    @Test
    void of_runsCapabilityEffectWithMatchingEnv() throws Throwable {
        Map<String, String> store = new HashMap<>();
        store.put("greeting", "hello");

        HandlerEnv<StoreOps> env = HandlerEnv.of(StoreOps.class, cap -> switch (cap) {
            case StoreOps.Get g -> store.getOrDefault(g.key(), "missing");
            case StoreOps.Put p -> { store.put(p.key(), p.value()); yield "ok"; }
        });

        EffectWithEnv<StoreOps, Throwable, String> effect =
                EffectWithEnv.<StoreOps, Throwable, String>of(
                        new StoreOps.Get("greeting").toEffect());

        String result = effect.run(env, runtime);

        assertEquals("hello", result);
    }

    @Test
    void of_putCapability_mutatesState() throws Throwable {
        Map<String, String> store = new HashMap<>();

        HandlerEnv<StoreOps> env = HandlerEnv.of(StoreOps.class, cap -> switch (cap) {
            case StoreOps.Get g -> store.getOrDefault(g.key(), "missing");
            case StoreOps.Put p -> { store.put(p.key(), p.value()); yield "ok"; }
        });

        EffectWithEnv<StoreOps, Throwable, String> effect =
                EffectWithEnv.<StoreOps, Throwable, String>of(
                        new StoreOps.Put("k", "v").toEffect());

        String result = effect.run(env, runtime);

        assertEquals("ok", result);
        assertEquals("v", store.get("k"));
    }

    // -----------------------------------------------------------------------
    // map() — transforms value, preserves R
    // -----------------------------------------------------------------------

    @Test
    void map_transformsResultValue() throws Throwable {
        EffectWithEnv<Empty, RuntimeException, String> base =
                EffectWithEnv.pure(Effect.succeed("hello"));

        EffectWithEnv<Empty, RuntimeException, Integer> mapped = base.map(String::length);

        Integer result = mapped.run(HandlerEnv.empty(), runtime);
        assertEquals(5, result);
    }

    @Test
    void map_preservesCapabilityRequirement() throws Throwable {
        Map<String, String> store = new HashMap<>();
        store.put("name", "Alice");

        HandlerEnv<StoreOps> env = HandlerEnv.of(StoreOps.class, cap -> switch (cap) {
            case StoreOps.Get g -> store.getOrDefault(g.key(), "missing");
            case StoreOps.Put p -> { store.put(p.key(), p.value()); yield "ok"; }
        });

        // map result is still EffectWithEnv<StoreOps, ...> — not EffectWithEnv<Empty, ...>
        EffectWithEnv<StoreOps, Throwable, Integer> nameLength =
                EffectWithEnv.<StoreOps, Throwable, String>of(
                        new StoreOps.Get("name").toEffect())
                        .map(String::length);

        Integer result = nameLength.run(env, runtime);
        assertEquals(5, result); // "Alice".length() == 5
    }

    // -----------------------------------------------------------------------
    // flatMap() — chains effects, preserves R
    // -----------------------------------------------------------------------

    @Test
    void flatMap_chainsEffects() throws Throwable {
        Map<String, String> store = new HashMap<>();
        store.put("key", "Alice");

        HandlerEnv<StoreOps> env = HandlerEnv.of(StoreOps.class, cap -> switch (cap) {
            case StoreOps.Get g -> store.getOrDefault(g.key(), "missing");
            case StoreOps.Put p -> { store.put(p.key(), p.value()); yield "ok"; }
        });

        // Get "key", then copy its value to "backup"
        EffectWithEnv<StoreOps, Throwable, String> effect =
                EffectWithEnv.<StoreOps, Throwable, String>of(
                        new StoreOps.Get("key").toEffect())
                        .flatMap(value ->
                                EffectWithEnv.of(new StoreOps.Put("backup", value).toEffect()));

        String result = effect.run(env, runtime);

        assertEquals("ok", result);
        assertEquals("Alice", store.get("backup"));
    }

    // -----------------------------------------------------------------------
    // Combined environment — With<StoreOps, LogOps>
    // -----------------------------------------------------------------------

    @Test
    void of_withCombinedEnv_handlesBothCapabilities() throws Throwable {
        Map<String, String> store = new HashMap<>();
        store.put("user", "Bob");
        StringBuilder log = new StringBuilder();

        HandlerEnv<With<StoreOps, LogOps>> env = HandlerEnv.of(StoreOps.class, cap -> switch (cap) {
            case StoreOps.Get g -> store.getOrDefault(g.key(), "missing");
            case StoreOps.Put p -> { store.put(p.key(), p.value()); yield "ok"; }
        }).and(HandlerEnv.of(LogOps.class, cap -> switch (cap) {
            case LogOps.Info m -> { log.append(m.message()); yield Unit.unit(); }
        }));

        Effect<Throwable, String> rawEffect = new StoreOps.Get("user").<Throwable>toEffect()
                .flatMap(name -> new LogOps.Info("got: " + name).<Throwable>toEffect()
                        .flatMap(__ -> Effect.succeed(name)));

        EffectWithEnv<With<StoreOps, LogOps>, Throwable, String> effect =
                EffectWithEnv.of(rawEffect);

        String result = effect.run(env, runtime);

        assertEquals("Bob", result);
        assertEquals("got: Bob", log.toString());
    }

    // -----------------------------------------------------------------------
    // effect() accessor
    // -----------------------------------------------------------------------

    @Test
    void effect_returnsUnderlyingEffect() throws Throwable {
        Effect<RuntimeException, Integer> underlying = Effect.succeed(99);
        EffectWithEnv<Empty, RuntimeException, Integer> wrapped = EffectWithEnv.pure(underlying);

        // effect() allows dropping back to untyped API
        Integer result = runtime.unsafeRun(wrapped.effect());
        assertEquals(99, result);
    }
}
