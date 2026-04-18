package com.cajunsystems.roux.rxjava;

import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.EffectRuntime;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;

import java.util.List;
import java.util.function.Supplier;

/**
 * Bidirectional bridge between the Roux effect system and RxJava 3.
 *
 * <h2>Effect → RxJava</h2>
 * <pre>{@code
 * Single<User> single = RxBridge.toSingle(fetchUser(id), runtime);
 * }</pre>
 *
 * <h2>RxJava → Effect</h2>
 * <pre>{@code
 * Effect<Throwable, User> effect = RxBridge.fromSingle(() -> userRepository.findById(id));
 * }</pre>
 */
public final class RxBridge {

    private RxBridge() {}

    // -----------------------------------------------------------------------
    // Single → Effect
    // -----------------------------------------------------------------------

    /**
     * Lift a lazily-created {@link Single} into an effect. The factory is called
     * only when the effect runs, so subscription is deferred until execution.
     *
     * <p>Prefer this overload when you control {@code Single} construction.
     */
    public static <A> Effect<Throwable, A> fromSingle(Supplier<Single<A>> singleFactory) {
        return Effect.suspend(() -> {
            try {
                return singleFactory.get().blockingGet();
            } catch (RuntimeException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception ex) throw ex;
                if (cause != null) throw new RuntimeException(cause);
                throw e;
            }
        });
    }

    /**
     * Lift an already-constructed {@link Single} into an effect. Subscription
     * happens when the effect runs.
     */
    public static <A> Effect<Throwable, A> fromSingle(Single<A> single) {
        return Effect.suspend(() -> {
            try {
                return single.blockingGet();
            } catch (RuntimeException e) {
                // RxJava wraps checked exceptions in RuntimeException — unwrap so callers
                // see the original exception type rather than an RxJava implementation detail.
                Throwable cause = e.getCause();
                if (cause instanceof Exception ex) throw ex;
                if (cause != null) throw new RuntimeException(cause);
                throw e;
            }
        });
    }

    // -----------------------------------------------------------------------
    // Effect → Single
    // -----------------------------------------------------------------------

    /**
     * Convert an effect to a {@link Single}. The effect is run asynchronously via
     * the provided runtime when the Single is subscribed to.
     *
     * <p>The returned Single is cold — execution begins on subscription, not here.
     */
    public static <A> Single<A> toSingle(Effect<?, A> effect, EffectRuntime runtime) {
        return Single.create(emitter -> {
            var handle = runtime.runAsync(
                    effect.widen(),
                    emitter::onSuccess,
                    emitter::onError
            );
            emitter.setCancellable(handle::cancel);
        });
    }

    // -----------------------------------------------------------------------
    // Observable → Effect
    // -----------------------------------------------------------------------

    /**
     * Collect all items from a lazily-created {@link Observable} into a {@link List}
     * and lift the result into an effect.
     */
    public static <A> Effect<Throwable, List<A>> fromObservable(Supplier<Observable<A>> observableFactory) {
        return Effect.suspend(() -> {
            try {
                return observableFactory.get().toList().blockingGet();
            } catch (RuntimeException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception ex) throw ex;
                if (cause != null) throw new RuntimeException(cause);
                throw e;
            }
        });
    }

    /**
     * Collect all items from an {@link Observable} into a {@link List} and lift into an effect.
     */
    public static <A> Effect<Throwable, List<A>> fromObservable(Observable<A> observable) {
        return fromSingle(observable.toList());
    }

    // -----------------------------------------------------------------------
    // Effect → Observable
    // -----------------------------------------------------------------------

    /**
     * Convert an effect that produces a {@link List} to an {@link Observable} that
     * emits each element. The effect is run when the Observable is subscribed to.
     */
    public static <A> Observable<A> toObservable(Effect<?, List<A>> effect, EffectRuntime runtime) {
        return toSingle(effect, runtime).flatMapObservable(Observable::fromIterable);
    }
}
