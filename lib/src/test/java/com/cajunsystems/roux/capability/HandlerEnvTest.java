package com.cajunsystems.roux.capability;

import com.cajunsystems.roux.data.Unit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HandlerEnvTest {

    // -----------------------------------------------------------------------
    // Test capability families (class-level — sealed interfaces cannot be local)
    // -----------------------------------------------------------------------

    sealed interface StoreOps extends Capability<String> {
        record Get(String key)                    implements StoreOps {}
        record Put(String key, String value)      implements StoreOps {}
    }

    sealed interface LogOps extends Capability<Unit> {
        record Info(String message)  implements LogOps {}
        record Error(String message) implements LogOps {}
    }

    sealed interface CountOps extends Capability<Integer> {
        record Increment() implements CountOps {}
        record Get()       implements CountOps {}
    }

    // -----------------------------------------------------------------------
    // HandlerEnv.empty()
    // -----------------------------------------------------------------------

    @Test
    void empty_throwsForAnyCapability() {
        HandlerEnv<Empty> env = HandlerEnv.empty();
        assertThrows(UnsupportedOperationException.class, () ->
                env.toHandler().handle(new StoreOps.Get("k")));
    }

    // -----------------------------------------------------------------------
    // HandlerEnv.of() — single capability family
    // -----------------------------------------------------------------------

    @Test
    void of_dispatchesGetToHandler() throws Exception {
        Map<String, String> store = new HashMap<>();
        store.put("user", "Alice");

        HandlerEnv<StoreOps> env = HandlerEnv.of(StoreOps.class, cap -> switch (cap) {
            case StoreOps.Get g -> store.getOrDefault(g.key(), "missing");
            case StoreOps.Put p -> { store.put(p.key(), p.value()); yield "ok"; }
        });

        String result = env.toHandler().handle(new StoreOps.Get("user"));
        assertEquals("Alice", result);
    }

    @Test
    void of_dispatchesPutToHandler() throws Exception {
        Map<String, String> store = new HashMap<>();

        HandlerEnv<StoreOps> env = HandlerEnv.of(StoreOps.class, cap -> switch (cap) {
            case StoreOps.Get g -> store.getOrDefault(g.key(), "missing");
            case StoreOps.Put p -> { store.put(p.key(), p.value()); yield "ok"; }
        });

        String result = env.toHandler().handle(new StoreOps.Put("k", "v"));
        assertEquals("ok", result);
        assertEquals("v", store.get("k"));
    }

    @Test
    void of_throwsForUnregisteredCapability() {
        HandlerEnv<StoreOps> env = HandlerEnv.of(StoreOps.class, cap -> switch (cap) {
            case StoreOps.Get g -> "value";
            case StoreOps.Put p -> "ok";
        });

        // LogOps.Info is not registered in storeEnv
        assertThrows(UnsupportedOperationException.class, () ->
                env.toHandler().handle(new LogOps.Info("hello")));
    }

    // -----------------------------------------------------------------------
    // HandlerEnv.and() — combine two environments
    // -----------------------------------------------------------------------

    @Test
    void and_combinesStoreAndLog() throws Exception {
        List<String> log = new ArrayList<>();
        Map<String, String> store = new HashMap<>();
        store.put("name", "Bob");

        HandlerEnv<StoreOps> storeEnv = HandlerEnv.of(StoreOps.class, cap -> switch (cap) {
            case StoreOps.Get g -> store.getOrDefault(g.key(), "missing");
            case StoreOps.Put p -> { store.put(p.key(), p.value()); yield "ok"; }
        });

        HandlerEnv<LogOps> logEnv = HandlerEnv.of(LogOps.class, cap -> switch (cap) {
            case LogOps.Info  m -> { log.add("INFO: "  + m.message()); yield Unit.unit(); }
            case LogOps.Error m -> { log.add("ERROR: " + m.message()); yield Unit.unit(); }
        });

        HandlerEnv<With<StoreOps, LogOps>> combined = storeEnv.and(logEnv);

        String name = combined.toHandler().handle(new StoreOps.Get("name"));
        combined.toHandler().handle(new LogOps.Info("fetched " + name));

        assertEquals("Bob", name);
        assertEquals(List.of("INFO: fetched Bob"), log);
    }

    @Test
    void and_throwsForUnregisteredCapability() {
        HandlerEnv<StoreOps> storeEnv = HandlerEnv.of(StoreOps.class, cap -> switch (cap) {
            case StoreOps.Get g -> "value";
            case StoreOps.Put p -> "ok";
        });
        HandlerEnv<LogOps> logEnv = HandlerEnv.of(LogOps.class, cap -> switch (cap) {
            case LogOps.Info  m -> Unit.unit();
            case LogOps.Error m -> Unit.unit();
        });

        HandlerEnv<With<StoreOps, LogOps>> combined = storeEnv.and(logEnv);

        // CountOps not registered
        assertThrows(UnsupportedOperationException.class, () ->
                combined.toHandler().handle(new CountOps.Get()));
    }

    @Test
    void and_isChainable_threeCapabilities() throws Exception {
        int[] counter = {0};

        HandlerEnv<StoreOps>  storeEnv = HandlerEnv.of(StoreOps.class,  cap -> "value");
        HandlerEnv<LogOps>    logEnv   = HandlerEnv.of(LogOps.class,    cap -> Unit.unit());
        HandlerEnv<CountOps>  countEnv = HandlerEnv.of(CountOps.class,  cap -> switch (cap) {
            case CountOps.Increment i -> ++counter[0];
            case CountOps.Get g       -> counter[0];
        });

        // With<StoreOps, With<LogOps, CountOps>> — right-nested by convention
        HandlerEnv<With<StoreOps, With<LogOps, CountOps>>> full =
                storeEnv.and(logEnv.and(countEnv));

        full.toHandler().handle(new CountOps.Increment());
        full.toHandler().handle(new CountOps.Increment());
        int count = full.toHandler().handle(new CountOps.Get());

        assertEquals(2, count);
    }

    // -----------------------------------------------------------------------
    // HandlerEnv.fromHandler() — escape hatch
    // -----------------------------------------------------------------------

    @Test
    void fromHandler_wrapsExistingHandler() throws Exception {
        CapabilityHandler<Capability<?>> legacy = CapabilityHandler.builder()
                .on(StoreOps.Get.class,  c -> "legacy-" + c.key())
                .on(StoreOps.Put.class,  c -> "ok")
                .build();

        HandlerEnv<StoreOps> env = HandlerEnv.fromHandler(legacy);

        String result = env.toHandler().handle(new StoreOps.Get("x"));
        assertEquals("legacy-x", result);
    }
}
