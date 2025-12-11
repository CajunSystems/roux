package com.cajunsystems.roux;

import java.util.function.BiFunction;

public final class Effects {
    
    private Effects() {
        throw new UnsupportedOperationException("Utility class");
    }
    
    public static <E extends Throwable, A, B, R> Effect<Throwable, R> par(
        Effect<E, A> ea,
        Effect<E, B> eb,
        BiFunction<A, B, R> f
    ) {
        return ea.zipPar(eb, f);
    }
    
    public static <E extends Throwable, A, B, C, R> Effect<Throwable, R> par(
        Effect<E, A> ea,
        Effect<E, B> eb,
        Effect<E, C> ec,
        Function3<A, B, C, R> f
    ) {
        return ea.fork().flatMap(fa ->
            eb.fork().flatMap(fb ->
                ec.fork().flatMap(fc ->
                    fa.join().mapError(e -> (Throwable) e).flatMap(a ->
                        fb.join().mapError(e -> (Throwable) e).flatMap(b ->
                            fc.join().mapError(e -> (Throwable) e).map(c ->
                                f.apply(a, b, c)
                            )
                        )
                    )
                )
            )
        );
    }
    
    public static <E extends Throwable, A, B, C, D, R> Effect<Throwable, R> par(
        Effect<E, A> ea,
        Effect<E, B> eb,
        Effect<E, C> ec,
        Effect<E, D> ed,
        Function4<A, B, C, D, R> f
    ) {
        return ea.fork().flatMap(fa ->
            eb.fork().flatMap(fb ->
                ec.fork().flatMap(fc ->
                    ed.fork().flatMap(fd ->
                        fa.join().mapError(e -> (Throwable) e).flatMap(a ->
                            fb.join().mapError(e -> (Throwable) e).flatMap(b ->
                                fc.join().mapError(e -> (Throwable) e).flatMap(c ->
                                    fd.join().mapError(e -> (Throwable) e).map(d ->
                                        f.apply(a, b, c, d)
                                    )
                                )
                            )
                        )
                    )
                )
            )
        );
    }
    
    @FunctionalInterface
    public interface Function3<A, B, C, R> {
        R apply(A a, B b, C c);
    }
    
    @FunctionalInterface
    public interface Function4<A, B, C, D, R> {
        R apply(A a, B b, C c, D d);
    }
    
    public record Tuple2<A, B>(A _1, B _2) {}
    
    public record Tuple3<A, B, C>(A _1, B _2, C _3) {}
}
