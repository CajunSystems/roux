package com.cajunsystems.roux;

import com.cajunsystems.roux.runtime.DefaultEffectRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.cajunsystems.roux.Effects.*;
import static org.junit.jupiter.api.Assertions.*;

class ZipParTest {
    private EffectRuntime runtime;
    
    @BeforeEach
    void setUp() {
        runtime = DefaultEffectRuntime.create();
    }
    
    @Test
    void testZipParWithTwoEffects() throws Throwable {
        Effect<Throwable, String> effect1 = Effect.suspend(() -> {
            Thread.sleep(100);
            return "Hello";
        });
        
        Effect<Throwable, String> effect2 = Effect.suspend(() -> {
            Thread.sleep(100);
            return "World";
        });
        
        Effect<Throwable, String> combined = effect1.zipPar(effect2, (a, b) -> a + " " + b);
        
        long start = System.currentTimeMillis();
        String result = runtime.unsafeRun(combined);
        long duration = System.currentTimeMillis() - start;
        
        assertEquals("Hello World", result);
        assertTrue(duration < 150, "Should run in parallel, not sequential");
    }
    
    @Test
    void testZipParWithMethodReference() throws Throwable {
        record User(String name, int age) {}
        
        Effect<Throwable, String> getName = Effect.succeed("Alice");
        Effect<Throwable, Integer> getAge = Effect.succeed(30);
        
        Effect<Throwable, User> user = getName.zipPar(getAge, User::new);
        
        User result = runtime.unsafeRun(user);
        
        assertEquals("Alice", result.name());
        assertEquals(30, result.age());
    }
    
    @Test
    void testParWithTwoEffects() throws Throwable {
        Effect<Throwable, Integer> effect1 = Effect.succeed(10);
        Effect<Throwable, Integer> effect2 = Effect.succeed(20);
        
        Effect<Throwable, Integer> sum = par(effect1, effect2, (a, b) -> a + b);
        
        Integer result = runtime.unsafeRun(sum);
        assertEquals(30, result);
    }
    
    @Test
    void testParWithThreeEffects() throws Throwable {
        Effect<Throwable, Integer> effect1 = Effect.succeed(10);
        Effect<Throwable, Integer> effect2 = Effect.succeed(20);
        Effect<Throwable, Integer> effect3 = Effect.succeed(30);
        
        Effect<Throwable, Integer> sum = par(
            effect1,
            effect2,
            effect3,
            (a, b, c) -> a + b + c
        );
        
        Integer result = runtime.unsafeRun(sum);
        assertEquals(60, result);
    }
    
    @Test
    void testParWithFourEffects() throws Throwable {
        Effect<Throwable, String> effect1 = Effect.succeed("A");
        Effect<Throwable, String> effect2 = Effect.succeed("B");
        Effect<Throwable, String> effect3 = Effect.succeed("C");
        Effect<Throwable, String> effect4 = Effect.succeed("D");
        
        Effect<Throwable, String> combined = par(
            effect1,
            effect2,
            effect3,
            effect4,
            (a, b, c, d) -> a + b + c + d
        );
        
        String result = runtime.unsafeRun(combined);
        assertEquals("ABCD", result);
    }
    
    @Test
    void testParWithRecordConstructor() throws Throwable {
        record Dashboard(String user, String orders, String preferences) {}
        
        Effect<Throwable, String> fetchUser = Effect.succeed("User Data");
        Effect<Throwable, String> fetchOrders = Effect.succeed("Orders Data");
        Effect<Throwable, String> fetchPrefs = Effect.succeed("Prefs Data");
        
        Effect<Throwable, Dashboard> dashboard = par(
            fetchUser,
            fetchOrders,
            fetchPrefs,
            Dashboard::new
        );
        
        Dashboard result = runtime.unsafeRun(dashboard);
        
        assertEquals("User Data", result.user());
        assertEquals("Orders Data", result.orders());
        assertEquals("Prefs Data", result.preferences());
    }
    
    @Test
    void testZipParComposition() throws Throwable {
        Effect<Throwable, Integer> effect1 = Effect.succeed(10);
        Effect<Throwable, Integer> effect2 = Effect.succeed(20);
        Effect<Throwable, Integer> effect3 = Effect.succeed(30);
        
        Effect<Throwable, Integer> result = effect1
            .zipPar(effect2, (a, b) -> a + b)
            .zipPar(effect3, (ab, c) -> ab + c);
        
        Integer value = runtime.unsafeRun(result);
        assertEquals(60, value);
    }
    
    @Test
    void testZipParWithErrorHandling() throws Throwable {
        Effect<RuntimeException, String> effect1 = Effect.succeed("Success");
        Effect<RuntimeException, String> effect2 = Effect.fail(new RuntimeException("Error"));
        
        Effect<Throwable, String> combined = effect1
            .zipPar(effect2, (a, b) -> a + " " + b)
            .catchAll(e -> Effect.succeed("Handled: " + e.getMessage()));
        
        String result = runtime.unsafeRun(combined);
        assertTrue(result.startsWith("Handled:"));
    }
}
