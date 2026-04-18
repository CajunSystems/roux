package com.cajunsystems.roux.reactor;

import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.runtime.DefaultEffectRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ReactorBridgeTest {

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
    // Mono → Effect
    // -----------------------------------------------------------------------

    @Test
    void fromMono_eagerVariant_resolvesValue() throws Throwable {
        Effect<Throwable, String> effect = ReactorBridge.fromMono(Mono.just("hello"));
        assertEquals("hello", runtime.unsafeRun(effect));
    }

    @Test
    void fromMono_lazyVariant_defersCreation() throws Throwable {
        AtomicBoolean created = new AtomicBoolean(false);
        Effect<Throwable, String> effect = ReactorBridge.fromMono(() -> {
            created.set(true);
            return Mono.just("lazy");
        });
        assertFalse(created.get());
        assertEquals("lazy", runtime.unsafeRun(effect));
        assertTrue(created.get());
    }

    @Test
    void fromMono_propagatesError() {
        Effect<Throwable, String> effect = ReactorBridge.fromMono(
                Mono.error(new IOException("mono failed"))
        );
        assertThrows(IOException.class, () -> runtime.unsafeRun(effect));
    }

    // -----------------------------------------------------------------------
    // Effect → Mono
    // -----------------------------------------------------------------------

    @Test
    void toMono_completesWithEffectResult() {
        Mono<String> mono = ReactorBridge.toMono(Effect.succeed("world"), runtime);
        assertEquals("world", mono.block());
    }

    @Test
    void toMono_propagatesEffectError() {
        Mono<String> mono = ReactorBridge.toMono(
                Effect.<IOException, String>fail(new IOException("effect failed")),
                runtime
        );
        assertThrows(RuntimeException.class, mono::block);
    }

    // -----------------------------------------------------------------------
    // Flux → Effect
    // -----------------------------------------------------------------------

    @Test
    void fromFlux_collectsAllItems() throws Throwable {
        Effect<Throwable, List<Integer>> effect = ReactorBridge.fromFlux(
                Flux.just(1, 2, 3)
        );
        assertEquals(List.of(1, 2, 3), runtime.unsafeRun(effect));
    }

    @Test
    void fromFlux_lazyVariant_defersCreation() throws Throwable {
        AtomicBoolean created = new AtomicBoolean(false);
        Effect<Throwable, List<String>> effect = ReactorBridge.fromFlux(() -> {
            created.set(true);
            return Flux.just("a", "b");
        });
        assertFalse(created.get());
        assertEquals(List.of("a", "b"), runtime.unsafeRun(effect));
        assertTrue(created.get());
    }

    // -----------------------------------------------------------------------
    // Effect → Flux
    // -----------------------------------------------------------------------

    @Test
    void toFlux_emitsAllElements() {
        Effect<RuntimeException, List<Integer>> effect = Effect.succeed(List.of(10, 20, 30));
        List<Integer> collected = ReactorBridge.toFlux(effect, runtime).collectList().block();
        assertEquals(List.of(10, 20, 30), collected);
    }
}
