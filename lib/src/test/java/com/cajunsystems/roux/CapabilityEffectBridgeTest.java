package com.cajunsystems.roux;

import com.cajunsystems.roux.capability.Capability;
import com.cajunsystems.roux.capability.CapabilityHandler;
import com.cajunsystems.roux.runtime.DefaultEffectRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.cajunsystems.roux.Effects.*;
import static org.junit.jupiter.api.Assertions.*;

class CapabilityEffectBridgeTest {
    private EffectRuntime runtime;
    
    @BeforeEach
    void setUp() {
        runtime = DefaultEffectRuntime.create();
    }
    
    sealed interface TestCapability<R> extends Capability<R> {
        record Log(String message) implements TestCapability<Void> {}
        record GetValue(String key) implements TestCapability<String> {}
        record SetValue(String key, String value) implements TestCapability<Void> {}
    }
    
    static class TestHandler implements CapabilityHandler<TestCapability<?>> {
        private final Map<String, String> store = new HashMap<>();
        private final List<String> logs = new ArrayList<>();
        
        @Override
        @SuppressWarnings("unchecked")
        public <R> R handle(TestCapability<?> capability) {
            return switch (capability) {
                case TestCapability.Log log -> {
                    logs.add(log.message());
                    yield (R) null;
                }
                case TestCapability.GetValue get -> 
                    (R) store.getOrDefault(get.key(), "default");
                case TestCapability.SetValue set -> {
                    store.put(set.key(), set.value());
                    yield (R) null;
                }
            };
        }
        
        List<String> getLogs() {
            return logs;
        }
        
        String getValue(String key) {
            return store.get(key);
        }
    }
    
    @Test
    void testCapabilityToEffect() throws Throwable {
        TestHandler handler = new TestHandler();
        
        Effect<Throwable, String> effect = new TestCapability.GetValue("name").toEffect();
        
        String result = runtime.unsafeRunWithHandler(effect, handler.widen());
        
        assertEquals("default", result);
    }
    
    @Test
    void testEffectFromCapability() throws Throwable {
        TestHandler handler = new TestHandler();
        
        Effect<Throwable, String> effect = Effect.from(new TestCapability.GetValue("name"));
        
        String result = runtime.unsafeRunWithHandler(effect, handler.widen());
        
        assertEquals("default", result);
    }
    
    @Test
    void testCapabilityWithMap() throws Throwable {
        TestHandler handler = new TestHandler();
        
        Effect<Throwable, Integer> effect = new TestCapability.GetValue("count")
            .toEffect()
            .map(s -> s.equals("default") ? 0 : Integer.parseInt(s));
        
        Integer result = runtime.unsafeRunWithHandler(effect, handler.widen());
        
        assertEquals(0, result);
    }
    
    @Test
    void testCapabilityWithFlatMap() throws Throwable {
        TestHandler handler = new TestHandler();
        
        Effect<Throwable, String> effect = new TestCapability.SetValue("name", "Alice")
            .toEffect()
            .flatMap(v -> new TestCapability.GetValue("name").toEffect());
        
        String result = runtime.unsafeRunWithHandler(effect, handler.widen());
        
        assertEquals("Alice", result);
        assertEquals("Alice", handler.getValue("name"));
    }
    
    @Test
    void testCapabilityWithZipPar() throws Throwable {
        TestHandler handler = new TestHandler();
        
        handler.handle(new TestCapability.SetValue("first", "Hello"));
        handler.handle(new TestCapability.SetValue("second", "World"));
        
        Effect<Throwable, String> effect = new TestCapability.GetValue("first")
            .toEffect()
            .zipPar(
                new TestCapability.GetValue("second").toEffect(),
                (a, b) -> a + " " + b
            );
        
        String result = runtime.unsafeRunWithHandler(effect, handler.widen());
        
        assertEquals("Hello World", result);
    }
    
    @Test
    void testCapabilityWithPar() throws Throwable {
        TestHandler handler = new TestHandler();
        
        record Result(String a, String b, String c) {}
        
        handler.handle(new TestCapability.SetValue("a", "One"));
        handler.handle(new TestCapability.SetValue("b", "Two"));
        handler.handle(new TestCapability.SetValue("c", "Three"));
        
        Effect<Throwable, Result> effect = par(
            new TestCapability.GetValue("a").toEffect(),
            new TestCapability.GetValue("b").toEffect(),
            new TestCapability.GetValue("c").toEffect(),
            Result::new
        );
        
        Result result = runtime.unsafeRunWithHandler(effect, handler.widen());
        
        assertEquals("One", result.a());
        assertEquals("Two", result.b());
        assertEquals("Three", result.c());
    }
    
    @Test
    void testGeneratorContextLift() throws Throwable {
        TestHandler handler = new TestHandler();
        
        Effect<Throwable, String> effect = Effect.generate(ctx -> {
            ctx.perform(new TestCapability.Log("Starting"));
            
            // Lift capability to effect for composition
            Effect<Throwable, String> getValue = ctx.lift(new TestCapability.GetValue("name"))
                .map(String::toUpperCase);
            
            String result = ctx.yield(getValue);
            
            ctx.perform(new TestCapability.Log("Done: " + result));
            
            return result;
        }, handler.widen());
        
        String result = runtime.unsafeRun(effect);
        
        assertEquals("DEFAULT", result);
        assertEquals(2, handler.getLogs().size());
        assertEquals("Starting", handler.getLogs().get(0));
        assertEquals("Done: DEFAULT", handler.getLogs().get(1));
    }
    
    @Test
    void testCapabilityComposition() throws Throwable {
        TestHandler handler = new TestHandler();
        
        Effect<Throwable, String> workflow = new TestCapability.Log("Step 1")
            .toEffect()
            .flatMap(v -> new TestCapability.SetValue("key", "value").toEffect())
            .flatMap(v -> new TestCapability.Log("Step 2").toEffect())
            .flatMap(v -> new TestCapability.GetValue("key").toEffect())
            .map(result -> "Result: " + result);
        
        String result = runtime.unsafeRunWithHandler(workflow, handler.widen());
        
        assertEquals("Result: value", result);
        assertEquals(2, handler.getLogs().size());
    }
    
    @Test
    void testCapabilityWithErrorHandling() throws Throwable {
        CapabilityHandler<Capability<?>> failingHandler = new CapabilityHandler<>() {
            @Override
            public <R> R handle(Capability<?> capability) throws Exception {
                throw new RuntimeException("Handler error");
            }
        };
        
        Effect<Throwable, String> effect = Effect.from(new TestCapability.GetValue("key"))
            .catchAll(e -> Effect.succeed("Handled: " + e.getMessage()));
        
        String result = runtime.unsafeRunWithHandler(effect, failingHandler);
        
        assertTrue(result.startsWith("Handled:"));
    }
}
