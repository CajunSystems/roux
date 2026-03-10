package com.cajunsystems.roux.capability;

import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.data.ThrowingFunction;

import java.util.function.Function;

/**
 * A recipe that, given a {@link HandlerEnv}{@code <RIn>}, builds a
 * {@link HandlerEnv}{@code <ROut>}, possibly performing effects during construction.
 *
 * <p>This is the direct analog of {@code ZLayer[RIn, E, ROut]} from ZIO.
 *
 * <h3>Leaf layer (no dependencies)</h3>
 * <pre>{@code
 * Layer<Empty, RuntimeException, StoreOps> storeLayer =
 *     Layer.succeed(StoreOps.class, cap -> switch (cap) {
 *         case StoreOps.Get g -> store.get(g.key());
 *         case StoreOps.Put p -> { store.put(p.key(), p.value()); yield "ok"; }
 *     });
 *
 * HandlerEnv<StoreOps> env = runtime.unsafeRun(storeLayer.build(HandlerEnv.empty()));
 * }</pre>
 *
 * <h3>Layer with dependencies</h3>
 * <pre>{@code
 * // emailLayer reads smtp host from configEnv during construction
 * Layer<ConfigOps, Exception, EmailOps> emailLayer = Layer.fromEffect(
 *     EmailOps.class,
 *     env -> Effect.suspend(() -> {
 *         String host = env.toHandler().handle(new ConfigOps.Get("smtp.host"));
 *         ThrowingFunction<EmailOps, String> handler = cap -> switch (cap) {
 *             case EmailOps.Send s -> sendVia(host, s.to());
 *         };
 *         return handler;
 *     })
 * );
 * }</pre>
 *
 * <h3>Building and using</h3>
 * <pre>{@code
 * // build() returns an Effect — run it to materialise the HandlerEnv
 * HandlerEnv<EmailOps> emailEnv = runtime.unsafeRun(emailLayer.build(configEnv));
 * }</pre>
 *
 * @param <RIn>  the capability environment this layer requires as input
 * @param <E>    the error type that may occur during layer construction
 * @param <ROut> the capability environment this layer produces
 */
@FunctionalInterface
public interface Layer<RIn, E extends Throwable, ROut> {

    /**
     * Build this layer: given the input environment, produce the output environment
     * as an {@link Effect}.
     *
     * <p>The returned effect may perform IO during construction — e.g., reading config,
     * establishing connections, or allocating resources.
     *
     * @param env the input capability environment
     * @return an effect that, when run, produces the output {@link HandlerEnv}
     */
    Effect<E, HandlerEnv<ROut>> build(HandlerEnv<RIn> env);

    // -----------------------------------------------------------------------
    // Factory methods
    // -----------------------------------------------------------------------

    /**
     * Create a leaf layer that requires no dependencies.
     *
     * <p>Uses the same F-bound as {@link HandlerEnv#of}: {@code C extends Capability<R>}
     * ensures the handler returns exactly the type {@code R} declared by the capability.
     *
     * <pre>{@code
     * Layer<Empty, RuntimeException, StoreOps> layer =
     *     Layer.succeed(StoreOps.class, cap -> switch (cap) {
     *         case StoreOps.Get g -> store.get(g.key());
     *         case StoreOps.Put p -> { store.put(p.key(), p.value()); yield "ok"; }
     *     });
     * }</pre>
     *
     * @param type    the capability family class
     * @param handler handler lambda for all subtypes of the capability family
     */
    static <R, C extends Capability<R>> Layer<Empty, RuntimeException, C> succeed(
            Class<C> type,
            ThrowingFunction<C, R> handler
    ) {
        return env -> Effect.succeed(HandlerEnv.of(type, handler));
    }

    /**
     * Create a layer whose handler is produced by running an {@link Effect}.
     *
     * <p>The {@code effectFn} receives the input {@link HandlerEnv}{@code <RIn>} and
     * returns an effect that produces the handler. This lets the layer read from its
     * input environment during construction:
     *
     * <pre>{@code
     * Layer<ConfigOps, Exception, EmailOps> emailLayer = Layer.fromEffect(
     *     EmailOps.class,
     *     env -> Effect.suspend(() -> {
     *         String host = env.toHandler().handle(new ConfigOps.Get("smtp.host"));
     *         ThrowingFunction<EmailOps, String> handler = cap -> switch (cap) {
     *             case EmailOps.Send s -> sendVia(host, s.to());
     *         };
     *         return handler;
     *     })
     * );
     * }</pre>
     *
     * @param type     the capability family class
     * @param effectFn function from input env to an effect producing the handler
     */
    static <RIn, E extends Throwable, R, C extends Capability<R>> Layer<RIn, E, C> fromEffect(
            Class<C> type,
            Function<HandlerEnv<RIn>, Effect<E, ThrowingFunction<C, R>>> effectFn
    ) {
        return env -> effectFn.apply(env).map(h -> {
            HandlerEnv<C> result = HandlerEnv.of(type, h);
            return result;
        });
    }
}
