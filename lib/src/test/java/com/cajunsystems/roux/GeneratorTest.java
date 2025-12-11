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

import static org.junit.jupiter.api.Assertions.*;

class GeneratorTest {
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
    }
    
    @Test
    void testBasicGenerator() throws Throwable {
        TestHandler handler = new TestHandler();
        
        Effect<Throwable, String> effect = Effect.generate(ctx -> {
            ctx.perform(new TestCapability.Log("Starting"));
            ctx.perform(new TestCapability.SetValue("name", "Alice"));
            String value = ctx.perform(new TestCapability.GetValue("name"));
            ctx.perform(new TestCapability.Log("Got value: " + value));
            return value;
        }, handler.widen());
        
        String result = runtime.unsafeRun(effect);
        
        assertEquals("Alice", result);
        assertEquals(2, handler.getLogs().size());
        assertEquals("Starting", handler.getLogs().get(0));
        assertEquals("Got value: Alice", handler.getLogs().get(1));
    }
    
    @Test
    void testGeneratorWithControlFlow() throws Throwable {
        TestHandler handler = new TestHandler();
        
        Effect<Throwable, Integer> effect = Effect.generate(ctx -> {
            int sum = 0;
            for (int i = 1; i <= 5; i++) {
                ctx.perform(new TestCapability.Log("Processing: " + i));
                sum += i;
            }
            ctx.perform(new TestCapability.Log("Sum: " + sum));
            return sum;
        }, handler.widen());
        
        Integer result = runtime.unsafeRun(effect);
        
        assertEquals(15, result);
        assertEquals(6, handler.getLogs().size());
    }
    
    @Test
    void testGeneratorYield() throws Throwable {
        TestHandler handler = new TestHandler();
        
        Effect<Throwable, String> innerEffect = Effect.succeed("inner");
        
        Effect<Throwable, String> effect = Effect.generate(ctx -> {
            ctx.perform(new TestCapability.Log("Before yield"));
            String inner = ctx.yield(innerEffect);
            ctx.perform(new TestCapability.Log("After yield: " + inner));
            return inner;
        }, handler.widen());
        
        String result = runtime.unsafeRun(effect);
        
        assertEquals("inner", result);
        assertEquals(2, handler.getLogs().size());
    }
    
    @Test
    void testGeneratorCall() throws Throwable {
        TestHandler handler = new TestHandler();
        
        Effect<Throwable, String> effect = Effect.generate(ctx -> {
            ctx.perform(new TestCapability.Log("Before call"));
            String result = ctx.call(() -> "direct-call");
            ctx.perform(new TestCapability.Log("After call: " + result));
            return result;
        }, handler.widen());
        
        String result = runtime.unsafeRun(effect);
        
        assertEquals("direct-call", result);
        assertEquals(2, handler.getLogs().size());
    }
    
    @Test
    void testGeneratorComposition() throws Throwable {
        TestHandler handler = new TestHandler();
        
        Effect<Throwable, String> effect1 = Effect.generate(ctx -> {
            ctx.perform(new TestCapability.Log("Effect 1"));
            return "result1";
        }, handler.widen());
        
        Effect<Throwable, String> effect2 = effect1.flatMap(r1 -> 
            Effect.generate(ctx -> {
                ctx.perform(new TestCapability.Log("Effect 2: " + r1));
                return r1 + "-result2";
            }, handler.widen())
        );
        
        String result = runtime.unsafeRun(effect2);
        
        assertEquals("result1-result2", result);
        assertEquals(2, handler.getLogs().size());
    }
}
