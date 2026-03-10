package com.cajunsystems.roux.capability;

import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.data.ThrowingFunction;

import java.util.function.Function;

/**
 * A recipe for building a {@link HandlerEnv}{@code <ROut>} from an existing
 * {@link HandlerEnv}{@code <RIn>}, potentially performing effects during construction.
 *
 * <p>Layers are the primary mechanism for <em>dependency tracking</em>: a layer declares
 * what capability environment it <strong>requires</strong> ({@code RIn}) and what it
 * <strong>provides</strong> ({@code ROut}).  This is the direct analog of
 * {@code ZLayer[RIn, E, ROut]} from ZIO.
 *
 * <h2>Composition</h2>
 * <p>Layers compose in two ways:
 * <dl>
 *   <dt><strong>Horizontal</strong> — {@link #and}:</dt>
 *   <dd>Both layers share the same input and their outputs are merged.
 *       {@code A ++ B → With<A, B>}</dd>
 *   <dt><strong>Vertical</strong> — {@link #andProvide}:</dt>
 *   <dd>The first layer's output is fed into the second layer as part of its input,
 *       and both outputs are retained.  {@code A >>> B → With<A, B>} (where {@code B}
 *       depends on {@code A}).</dd>
 * </dl>
 *
 * <h2>Building an environment</h2>
 * <pre>{@code
 * // Leaf layer — no dependencies
 * Layer<Empty, RuntimeException, DbOps> dbLayer =
 *     Layer.succeed(DbOps.class, cap -> switch (cap) {
 *         case DbOps.Query  q -> executeQuery(q.sql());
 *         case DbOps.Execute e -> { executeUpdate(e.sql()); yield null; }
 *     });
 *
 * // Another leaf
 * Layer<Empty, RuntimeException, EmailOps> emailLayer = Layer.succeed(EmailOps.class, ...);
 *
 * // Horizontal composition — both provided from the empty environment
 * Layer<Empty, Throwable, With<DbOps, EmailOps>> appLayer = dbLayer.and(emailLayer);
 *
 * // Build into a typed env
 * HandlerEnv<With<DbOps, EmailOps>> env =
 *     runtime.unsafeRun(appLayer.build(HandlerEnv.empty()));
 * }</pre>
 *
 * <h2>Layer with dependencies</h2>
 * <pre>{@code
 * // Config layer — no deps
 * Layer<Empty, RuntimeException, ConfigOps> configLayer = Layer.succeed(ConfigOps.class, ...);
 *
 * // Email layer — needs ConfigOps to read SMTP settings
 * Layer<ConfigOps, Exception, EmailOps> emailLayer = Layer.fromEffect(
 *     EmailOps.class,
 *     configEnv -> Effect.suspend(() -> {
 *         String smtpHost = configEnv.toHandler().handle(new ConfigOps.Get("smtp.host"));
 *         return (EmailOps cap) -> sendViaSmtp(smtpHost, cap);
 *     })
 * );
 *
 * // Vertical composition: configLayer provides what emailLayer needs
 * Layer<Empty, Throwable, With<ConfigOps, EmailOps>> full = configLayer.andProvide(emailLayer);
 * }</pre>
 *
 * @param <RIn>  phantom type encoding what this layer requires from the environment
 * @param <E>    error type that may be thrown while building this layer
 * @param <ROut> phantom type encoding what this layer provides
 */
@FunctionalInterface
public interface Layer<RIn, E extends Throwable, ROut> {

    /**
     * Build the output environment given the input environment.
     *
     * @param input the capability environment available during construction
     * @return an effect that, when run, produces the output environment
     */
    Effect<E, HandlerEnv<ROut>> build(HandlerEnv<RIn> input);

    // -----------------------------------------------------------------------
    // Horizontal composition — same input, merge outputs
    // -----------------------------------------------------------------------

    /**
     * Combine this layer with another that shares the same input, producing a layer
     * whose output covers both.
     *
     * <p>Both layers are built against the same input environment and their outputs
     * are merged with {@link HandlerEnv#and}.
     *
     * <pre>{@code
     * Layer<Empty, Throwable, With<DbOps, EmailOps>> app = dbLayer.and(emailLayer);
     * }</pre>
     *
     * @param other the layer to combine with this one
     * @param <E2>    error type of the other layer (widened to {@link Throwable})
     * @param <ROut2> the other layer's output type
     * @return a layer producing {@code With<ROut, ROut2>}
     */
    default <E2 extends Throwable, ROut2> Layer<RIn, Throwable, With<ROut, ROut2>> and(
            Layer<RIn, E2, ROut2> other
    ) {
        Layer<RIn, E, ROut> self = this;
        return input -> self.build(input)
                .widen()
                .flatMap(outA -> other.build(input)
                        .widen()
                        .map(outA::and));
    }

    // -----------------------------------------------------------------------
    // Vertical composition — feed this layer's output into the next layer's input
    // -----------------------------------------------------------------------

    /**
     * Compose this layer with a downstream layer that depends on this layer's output.
     * The downstream layer receives this layer's output as its input environment, and
     * both outputs are retained in the result.
     *
     * <p>This is the {@code >>>} (pipe / feed-forward) operator from ZIO layers:
     * <pre>{@code
     * // configLayer : Empty     → ConfigOps
     * // emailLayer  : ConfigOps → EmailOps  (reads config during construction)
     * // combined    : Empty     → With<ConfigOps, EmailOps>
     * Layer<Empty, Throwable, With<ConfigOps, EmailOps>> combined =
     *     configLayer.andProvide(emailLayer);
     * }</pre>
     *
     * @param next    the downstream layer that consumes this layer's output as input
     * @param <E2>    error type of the downstream layer (widened to {@link Throwable})
     * @param <ROut2> the downstream layer's output type
     * @return a layer producing {@code With<ROut, ROut2>} from {@code RIn}
     */
    default <E2 extends Throwable, ROut2> Layer<RIn, Throwable, With<ROut, ROut2>> andProvide(
            Layer<ROut, E2, ROut2> next
    ) {
        Layer<RIn, E, ROut> self = this;
        return input -> self.build(input)
                .widen()
                .flatMap(outA -> next.build(outA)
                        .widen()
                        .map(outA::and));
    }

    // -----------------------------------------------------------------------
    // Factory methods
    // -----------------------------------------------------------------------

    /**
     * Create a pure layer with no dependencies that builds a handler from a simple lambda.
     *
     * <pre>{@code
     * Layer<Empty, RuntimeException, DbOps> dbLayer = Layer.succeed(DbOps.class, cap ->
     *     switch (cap) {
     *         case DbOps.Query q  -> executeQuery(q.sql());
     *         case DbOps.Execute e -> { executeUpdate(e.sql()); yield null; }
     *     }
     * );
     * }</pre>
     *
     * @param type    the root capability interface
     * @param handler lambda that handles all subtypes of {@code C}
     * @param <C>     the capability type this layer provides
     * @return a layer with no input dependencies that provides {@code C}
     */
    static <C extends Capability<?>> Layer<Empty, RuntimeException, C> succeed(
            Class<C> type,
            ThrowingFunction<C, ?> handler
    ) {
        HandlerEnv<C> env = HandlerEnv.of(type, handler);
        return __ -> Effect.succeed(env);
    }

    /**
     * Create a layer whose handler is built by an effect that can read from the input
     * environment.  Use this when handler construction itself requires performing
     * capabilities or other effectful initialisation.
     *
     * <p>The {@code handlerFactory} receives the input {@link HandlerEnv} and returns an
     * effect that produces the handler function for {@code C}.
     *
     * <pre>{@code
     * Layer<ConfigOps, Exception, EmailOps> emailLayer = Layer.fromEffect(
     *     EmailOps.class,
     *     configEnv -> Effect.suspend(() -> {
     *         String host = configEnv.toHandler().handle(new ConfigOps.Get("smtp.host"));
     *         return (EmailOps cap) -> switch (cap) {
     *             case EmailOps.Send s -> sendViaSmtp(host, s.to(), s.body());
     *         };
     *     })
     * );
     * }</pre>
     *
     * @param type           the root capability interface
     * @param handlerFactory function from input env to an effect producing the handler function
     * @param <RIn>          what the layer requires
     * @param <E>            error type during construction
     * @param <C>            the capability type this layer provides
     * @return a layer that builds {@code C} handlers from {@code RIn}
     */
    static <RIn, E extends Throwable, C extends Capability<?>> Layer<RIn, E, C> fromEffect(
            Class<C> type,
            Function<HandlerEnv<RIn>, Effect<E, ThrowingFunction<C, ?>>> handlerFactory
    ) {
        return input -> handlerFactory.apply(input)
                .map(handler -> HandlerEnv.of(type, handler));
    }

    /**
     * Create a layer from a pre-built {@link CapabilityHandler}.
     *
     * <p>This is an escape hatch for integrating existing handlers into the typed layer
     * system.  The phantom type {@code C} must accurately reflect what {@code handler}
     * actually handles — this is not verified at runtime.
     *
     * @param handler an existing capability handler
     * @param <C>     the capability type (phantom)
     * @return a layer with no input dependencies that wraps the given handler
     */
    static <C extends Capability<?>> Layer<Empty, RuntimeException, C> fromHandler(
            CapabilityHandler<C> handler
    ) {
        HandlerEnv<C> env = HandlerEnv.fromHandler(handler);
        return __ -> Effect.succeed(env);
    }
}
