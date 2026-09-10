package com.cajunsystems.roux.rxjava;

import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.runtime.DefaultEffectRuntime;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class RxBridgeTest {

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
    // Single → Effect
    // -----------------------------------------------------------------------

    @Test
    void fromSingle_eagerVariant_resolvesValue() throws Throwable {
        Effect<Throwable, String> effect = RxBridge.fromSingle(Single.just("hello"));
        assertEquals("hello", runtime.unsafeRun(effect));
    }

    @Test
    void fromSingle_lazyVariant_defersCreation() throws Throwable {
        AtomicBoolean created = new AtomicBoolean(false);
        Effect<Throwable, String> effect = RxBridge.fromSingle(() -> {
            created.set(true);
            return Single.just("lazy");
        });
        assertFalse(created.get());
        assertEquals("lazy", runtime.unsafeRun(effect));
        assertTrue(created.get());
    }

    @Test
    void fromSingle_propagatesError() {
        Effect<Throwable, String> effect = RxBridge.fromSingle(
                Single.error(new IOException("rx failed"))
        );
        assertThrows(IOException.class, () -> runtime.unsafeRun(effect));
    }

    // -----------------------------------------------------------------------
    // Effect → Single
    // -----------------------------------------------------------------------

    @Test
    void toSingle_completesWithEffectResult() {
        Single<String> single = RxBridge.toSingle(Effect.succeed("world"), runtime);
        assertEquals("world", single.blockingGet());
    }

    @Test
    void toSingle_propagatesEffectError() {
        Single<String> single = RxBridge.toSingle(
                Effect.<IOException, String>fail(new IOException("effect failed")),
                runtime
        );
        assertThrows(RuntimeException.class, single::blockingGet);
    }

    // -----------------------------------------------------------------------
    // Observable → Effect
    // -----------------------------------------------------------------------

    @Test
    void fromObservable_collectsAllItems() throws Throwable {
        Effect<Throwable, List<Integer>> effect = RxBridge.fromObservable(
                Observable.just(1, 2, 3)
        );
        assertEquals(List.of(1, 2, 3), runtime.unsafeRun(effect));
    }

    @Test
    void fromObservable_lazyVariant_defersCreation() throws Throwable {
        AtomicBoolean created = new AtomicBoolean(false);
        Effect<Throwable, List<String>> effect = RxBridge.fromObservable(() -> {
            created.set(true);
            return Observable.just("x", "y");
        });
        assertFalse(created.get());
        assertEquals(List.of("x", "y"), runtime.unsafeRun(effect));
        assertTrue(created.get());
    }

    // -----------------------------------------------------------------------
    // Effect → Observable
    // -----------------------------------------------------------------------

    @Test
    void toObservable_emitsAllElements() {
        Effect<RuntimeException, List<Integer>> effect = Effect.succeed(List.of(10, 20, 30));
        List<Integer> collected = RxBridge.toObservable(effect, runtime).toList().blockingGet();
        assertEquals(List.of(10, 20, 30), collected);
    }
}
