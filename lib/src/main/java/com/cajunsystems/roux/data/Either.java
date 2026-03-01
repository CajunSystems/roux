package com.cajunsystems.roux.data;

import java.util.function.Function;

public sealed interface Either<L, R> {

    record Left<L, R>(L value) implements Either<L, R> {}
    record Right<L, R>(R value) implements Either<L, R> {}

    // -----------------------------------------------------------------------
    // Smart constructors
    // -----------------------------------------------------------------------

    static <L, R> Either<L, R> left(L value) { return new Left<>(value); }
    static <L, R> Either<L, R> right(R value) { return new Right<>(value); }

    // -----------------------------------------------------------------------
    // Query
    // -----------------------------------------------------------------------

    default boolean isLeft() { return this instanceof Left<L, R>; }
    default boolean isRight() { return this instanceof Right<L, R>; }

    // -----------------------------------------------------------------------
    // Transformation
    // -----------------------------------------------------------------------

    /**
     * Transform the right (success) value, leaving the left unchanged.
     */
    default <R2> Either<L, R2> map(Function<R, R2> f) {
        return switch (this) {
            case Left<L, R> left -> Either.left(left.value());
            case Right<L, R> right -> Either.right(f.apply(right.value()));
        };
    }

    /**
     * Flat-map over the right (success) value.
     */
    default <R2> Either<L, R2> flatMap(Function<R, Either<L, R2>> f) {
        return switch (this) {
            case Left<L, R> left -> Either.left(left.value());
            case Right<L, R> right -> f.apply(right.value());
        };
    }

    /**
     * Transform the left (error) value, leaving the right unchanged.
     */
    default <L2> Either<L2, R> mapLeft(Function<L, L2> f) {
        return switch (this) {
            case Left<L, R> left -> Either.left(f.apply(left.value()));
            case Right<L, R> right -> Either.right(right.value());
        };
    }

    /**
     * Collapse both sides into a single value by applying the appropriate function.
     */
    default <T> T fold(Function<L, T> onLeft, Function<R, T> onRight) {
        return switch (this) {
            case Left<L, R> left -> onLeft.apply(left.value());
            case Right<L, R> right -> onRight.apply(right.value());
        };
    }

    /**
     * Return the right value, or {@code defaultValue} if this is a Left.
     */
    default R getOrElse(R defaultValue) {
        return switch (this) {
            case Left<L, R> __ -> defaultValue;
            case Right<L, R> right -> right.value();
        };
    }

    /**
     * Return the right value, or compute a fallback from the left value.
     */
    default R getOrElse(Function<L, R> fallback) {
        return switch (this) {
            case Left<L, R> left -> fallback.apply(left.value());
            case Right<L, R> right -> right.value();
        };
    }

    /**
     * Swap left and right sides.
     */
    default Either<R, L> swap() {
        return switch (this) {
            case Left<L, R> left -> Either.right(left.value());
            case Right<L, R> right -> Either.left(right.value());
        };
    }
}
