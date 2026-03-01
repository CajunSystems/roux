package com.cajunsystems.roux;

import com.cajunsystems.roux.capability.Capability;
import com.cajunsystems.roux.capability.CapabilityHandler;
import com.cajunsystems.roux.capability.CompositeCapabilityHandler;
import com.cajunsystems.roux.runtime.DefaultEffectRuntime;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CapabilityHandlerBuilderTest {

    sealed interface AppCapability<R> extends Capability<R> {
        record Log(String msg) implements AppCapability<Void> {}
        record GetValue(String key) implements AppCapability<String> {}
        record SetValue(String key, String val) implements AppCapability<Void> {}
    }

    @Test
    void builderDispatchesOnExactType() throws Exception {
        List<String> log = new ArrayList<>();
        CapabilityHandler<Capability<?>> h = CapabilityHandler.builder()
                .on(AppCapability.Log.class,      c -> { log.add(c.msg()); return null; })
                .on(AppCapability.GetValue.class, c -> "value-of-" + c.key())
                .build();

        h.handle(new AppCapability.Log("hello"));
        String val = h.handle(new AppCapability.GetValue("x"));

        assertEquals(List.of("hello"), log);
        assertEquals("value-of-x", val);
    }

    @Test
    void builderThrowsUnsupportedOperationForUnregisteredType() {
        CapabilityHandler<Capability<?>> h = CapabilityHandler.builder()
                .on(AppCapability.Log.class, c -> null)
                .build();
        assertThrows(UnsupportedOperationException.class,
                () -> h.handle(new AppCapability.GetValue("key")));
    }

    @Test
    void builderHandlesMultipleTypes() throws Exception {
        Map<String, String> store = new HashMap<>();
        CapabilityHandler<Capability<?>> h = CapabilityHandler.builder()
                .on(AppCapability.SetValue.class, c -> { store.put(c.key(), c.val()); return null; })
                .on(AppCapability.GetValue.class, c -> store.getOrDefault(c.key(), "missing"))
                .build();

        h.handle(new AppCapability.SetValue("foo", "bar"));
        assertEquals("bar", (String) h.handle(new AppCapability.GetValue("foo")));
    }

    @Test
    void composeTriesHandlersInOrder() throws Exception {
        List<String> log = new ArrayList<>();
        CapabilityHandler<Capability<?>> logH = CapabilityHandler.builder()
                .on(AppCapability.Log.class, c -> { log.add(c.msg()); return null; })
                .build();
        CapabilityHandler<Capability<?>> getH = CapabilityHandler.builder()
                .on(AppCapability.GetValue.class, c -> "got-" + c.key())
                .build();
        CapabilityHandler<Capability<?>> combined = CapabilityHandler.compose(logH, getH);

        combined.handle(new AppCapability.Log("test"));
        String val = combined.handle(new AppCapability.GetValue("k"));

        assertEquals(List.of("test"), log);
        assertEquals("got-k", val);
    }

    @Test
    void composeThrowsWhenNoHandlerMatches() {
        CapabilityHandler<Capability<?>> logOnly = CapabilityHandler.builder()
                .on(AppCapability.Log.class, c -> null)
                .build();
        assertThrows(UnsupportedOperationException.class,
                () -> CapabilityHandler.compose(logOnly).handle(new AppCapability.GetValue("k")));
    }

    @Test
    void orElseFallsBackWhenPrimaryThrowsUnsupported() throws Exception {
        CapabilityHandler<Capability<?>> primary = CapabilityHandler.builder()
                .on(AppCapability.Log.class, c -> null).build();
        CapabilityHandler<Capability<?>> fallback = CapabilityHandler.builder()
                .on(AppCapability.GetValue.class, c -> "fallback").build();

        CapabilityHandler<Capability<?>> combined = primary.orElse(fallback);
        combined.handle(new AppCapability.Log("hi"));
        assertEquals("fallback", (String) combined.handle(new AppCapability.GetValue("x")));
    }

    static class MyCompositeHandler extends CompositeCapabilityHandler {
        final List<String> logs = new ArrayList<>();
        final Map<String, String> store = new HashMap<>();

        @SuppressWarnings("unchecked")
        MyCompositeHandler() {
            register(AppCapability.Log.class, new CapabilityHandler<AppCapability.Log>() {
                public <R> R handle(AppCapability.Log c) { logs.add(c.msg()); return (R)(Void)null; }
            });
            register(AppCapability.GetValue.class, new CapabilityHandler<AppCapability.GetValue>() {
                @SuppressWarnings("unchecked")
                public <R> R handle(AppCapability.GetValue c) { return (R) store.getOrDefault(c.key(), "default"); }
            });
            register(AppCapability.SetValue.class, new CapabilityHandler<AppCapability.SetValue>() {
                public <R> R handle(AppCapability.SetValue c) { store.put(c.key(), c.val()); return (R)(Void)null; }
            });
        }
    }

    @Test
    void compositeHandlerRoutesCorrectly() throws Exception {
        MyCompositeHandler h = new MyCompositeHandler();
        h.handle(new AppCapability.SetValue("k", "v"));
        String val = h.handle(new AppCapability.GetValue("k"));
        h.handle(new AppCapability.Log("done"));

        assertEquals("v", val);
        assertEquals(List.of("done"), h.logs);
    }

    @Test
    void compositeHandlerThrowsOnUnregisteredType() {
        assertThrows(UnsupportedOperationException.class,
                () -> new CompositeCapabilityHandler() {}.handle(new AppCapability.Log("x")));
    }

    @Test
    void builderHandlerWorksWithEffectRuntime() throws Throwable {
        List<String> logged = new ArrayList<>();
        CapabilityHandler<Capability<?>> h = CapabilityHandler.builder()
                .on(AppCapability.Log.class, c -> { logged.add(c.msg()); return null; })
                .on(AppCapability.GetValue.class, c -> "hello")
                .build();

        Effect<Throwable, String> effect = new AppCapability.Log("start")
                .toEffect()
                .flatMap(__ -> new AppCapability.GetValue("name").toEffect())
                .tap(val -> logged.add("got: " + val));

        try (DefaultEffectRuntime runtime = DefaultEffectRuntime.create()) {
            String result = runtime.unsafeRunWithHandler(effect, h);
            assertEquals("hello", result);
        }
        assertEquals(List.of("start", "got: hello"), logged);
    }

    @Test
    void defaultEffectRuntimeIsAutoCloseable() {
        assertDoesNotThrow(() -> {
            try (DefaultEffectRuntime r = DefaultEffectRuntime.create()) {
                r.unsafeRun(Effect.succeed(42));
            }
        });
    }
}
