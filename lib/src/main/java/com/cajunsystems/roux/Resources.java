package com.cajunsystems.roux;

import com.cajunsystems.roux.Effects.Function3;

import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Fluent builder for acquiring multiple {@link Resource}s without nesting.
 *
 * <h2>Before (nested)</h2>
 * <pre>{@code
 * connResource.use(conn ->
 *     stmtResource.use(stmt ->
 *         txResource.use(tx -> doWork(conn, stmt, tx))));
 * }</pre>
 *
 * <h2>After (flat)</h2>
 * <pre>{@code
 * Resources.with(connResource)
 *          .and(stmtResource)
 *          .and(txResource)
 *          .use((conn, stmt, tx) -> doWork(conn, stmt, tx));
 * }</pre>
 *
 * Resources are acquired in declaration order and released in reverse order,
 * exactly as with {@link Resource#flatMap} composition.
 */
public final class Resources {

    private Resources() {}

    public static <A> Builder1<A> with(Resource<A> resource) {
        return new Builder1<>(resource);
    }

    // -----------------------------------------------------------------------
    // Builder1 — one resource
    // -----------------------------------------------------------------------

    public static final class Builder1<A> {
        private final Resource<A> r1;

        private Builder1(Resource<A> r1) {
            this.r1 = r1;
        }

        public <B> Builder2<A, B> and(Resource<B> r2) {
            return new Builder2<>(r1, r2);
        }

        public <E extends Throwable, R> Effect<Throwable, R> use(Function<A, Effect<E, R>> f) {
            return r1.use(f);
        }
    }

    // -----------------------------------------------------------------------
    // Builder2 — two resources
    // -----------------------------------------------------------------------

    public static final class Builder2<A, B> {
        private final Resource<A> r1;
        private final Resource<B> r2;

        private Builder2(Resource<A> r1, Resource<B> r2) {
            this.r1 = r1;
            this.r2 = r2;
        }

        public <C> Builder3<A, B, C> and(Resource<C> r3) {
            return new Builder3<>(r1, r2, r3);
        }

        public <E extends Throwable, R> Effect<Throwable, R> use(BiFunction<A, B, Effect<E, R>> f) {
            Resource<Pair<A, B>> combined = r1.flatMap(a -> r2.map(b -> new Pair<>(a, b)));
            return combined.use(p -> f.apply(p.first(), p.second()));
        }
    }

    // -----------------------------------------------------------------------
    // Builder3 — three resources
    // -----------------------------------------------------------------------

    public static final class Builder3<A, B, C> {
        private final Resource<A> r1;
        private final Resource<B> r2;
        private final Resource<C> r3;

        private Builder3(Resource<A> r1, Resource<B> r2, Resource<C> r3) {
            this.r1 = r1;
            this.r2 = r2;
            this.r3 = r3;
        }

        public <E extends Throwable, R> Effect<Throwable, R> use(Function3<A, B, C, Effect<E, R>> f) {
            Resource<Triple<A, B, C>> combined = r1.flatMap(a ->
                    r2.flatMap(b ->
                            r3.map(c -> new Triple<>(a, b, c))));
            return combined.use(t -> f.apply(t.first(), t.second(), t.third()));
        }
    }

    // -----------------------------------------------------------------------
    // Internal product types (package-private for tests)
    // -----------------------------------------------------------------------

    record Pair<A, B>(A first, B second) {}
    record Triple<A, B, C>(A first, B second, C third) {}
}
