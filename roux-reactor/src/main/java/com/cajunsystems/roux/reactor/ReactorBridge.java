package com.cajunsystems.roux.reactor;

import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.EffectRuntime;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.function.Supplier;

/**
 * Bidirectional bridge between the Roux effect system and Project Reactor.
 *
 * <h2>Effect → Reactor</h2>
 * <pre>{@code
 * // Use in a Spring WebFlux controller:
 * @GetMapping("/user/{id}")
 * public Mono<User> getUser(@PathVariable String id) {
 *     return ReactorBridge.toMono(fetchUser(id), runtime);
 * }
 * }</pre>
 *
 * <h2>Reactor → Effect</h2>
 * <pre>{@code
 * // Lift an existing reactive call into the effect system:
 * Effect<Throwable, User> effect = ReactorBridge.fromMono(() -> userRepository.findById(id));
 * }</pre>
 */
public final class ReactorBridge {

    private ReactorBridge() {}

    // -----------------------------------------------------------------------
    // Mono → Effect
    // -----------------------------------------------------------------------

    /**
     * Lift a lazily-created {@link Mono} into an effect. The factory is called only
     * when the effect runs, so subscription does not happen until execution.
     *
     * <p>Prefer this overload when you control {@code Mono} construction.
     */
    public static <A> Effect<Throwable, A> fromMono(Supplier<Mono<A>> monoFactory) {
        return Effect.suspend(() -> {
            try {
                return monoFactory.get().block();
            } catch (RuntimeException e) {
                Throwable unwrapped = Exceptions.unwrap(e);
                if (unwrapped instanceof Exception ex) throw ex;
                throw new RuntimeException(unwrapped);
            }
        });
    }

    /**
     * Lift an already-constructed {@link Mono} into an effect. Subscription (and
     * any side effects in the Mono pipeline) happens when the effect runs.
     */
    public static <A> Effect<Throwable, A> fromMono(Mono<A> mono) {
        return Effect.suspend(() -> {
            try {
                return mono.block();
            } catch (RuntimeException e) {
                // Reactor wraps checked exceptions in ReactiveException — unwrap them so callers
                // see the original exception type rather than a Reactor implementation detail.
                Throwable unwrapped = Exceptions.unwrap(e);
                if (unwrapped instanceof Exception ex) throw ex;
                throw new RuntimeException(unwrapped);
            }
        });
    }

    // -----------------------------------------------------------------------
    // Effect → Mono
    // -----------------------------------------------------------------------

    /**
     * Convert an effect to a {@link Mono}. The effect is run asynchronously via
     * the provided runtime when the Mono is subscribed to.
     *
     * <p>The returned Mono is cold — execution begins on subscription, not here.
     */
    public static <A> Mono<A> toMono(Effect<?, A> effect, EffectRuntime runtime) {
        return Mono.create(sink -> {
            var handle = runtime.runAsync(
                    effect.widen(),
                    sink::success,
                    sink::error
            );
            sink.onCancel(handle::cancel);
            sink.onDispose(handle::cancel);
        });
    }

    // -----------------------------------------------------------------------
    // Flux → Effect
    // -----------------------------------------------------------------------

    /**
     * Collect all items from a lazily-created {@link Flux} into a {@link List}
     * and lift the result into an effect.
     */
    public static <A> Effect<Throwable, List<A>> fromFlux(Supplier<Flux<A>> fluxFactory) {
        return Effect.suspend(() -> {
            try {
                return fluxFactory.get().collectList().block();
            } catch (RuntimeException e) {
                Throwable unwrapped = Exceptions.unwrap(e);
                if (unwrapped instanceof Exception ex) throw ex;
                throw new RuntimeException(unwrapped);
            }
        });
    }

    /**
     * Collect all items from a {@link Flux} into a {@link List} and lift into an effect.
     */
    public static <A> Effect<Throwable, List<A>> fromFlux(Flux<A> flux) {
        return fromMono(flux.collectList());
    }

    // -----------------------------------------------------------------------
    // Effect → Flux
    // -----------------------------------------------------------------------

    /**
     * Convert an effect that produces a {@link List} to a {@link Flux} that emits
     * each element. The effect is run when the Flux is subscribed to.
     */
    public static <A> Flux<A> toFlux(Effect<?, List<A>> effect, EffectRuntime runtime) {
        return toMono(effect, runtime).flatMapIterable(list -> list);
    }
}
