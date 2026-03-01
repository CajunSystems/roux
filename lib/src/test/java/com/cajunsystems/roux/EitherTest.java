package com.cajunsystems.roux;

import com.cajunsystems.roux.data.Either;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the enriched Either type added in 0.2.0.
 */
class EitherTest {

    // -----------------------------------------------------------------------
    // Basic construction
    // -----------------------------------------------------------------------

    @Test
    void leftIsLeft() {
        Either<String, Integer> e = Either.left("error");
        assertTrue(e.isLeft());
        assertFalse(e.isRight());
    }

    @Test
    void rightIsRight() {
        Either<String, Integer> e = Either.right(42);
        assertTrue(e.isRight());
        assertFalse(e.isLeft());
    }

    // -----------------------------------------------------------------------
    // map
    // -----------------------------------------------------------------------

    @Test
    void mapTransformsRightValue() {
        Either<String, Integer> e = Either.right(10);
        Either<String, String> mapped = e.map(n -> "n=" + n);

        assertInstanceOf(Either.Right.class, mapped);
        assertEquals("n=10", ((Either.Right<String, String>) mapped).value());
    }

    @Test
    void mapDoesNotAffectLeft() {
        Either<String, Integer> e = Either.left("error");
        Either<String, String> mapped = e.map(n -> "n=" + n);

        assertInstanceOf(Either.Left.class, mapped);
        assertEquals("error", ((Either.Left<String, String>) mapped).value());
    }

    // -----------------------------------------------------------------------
    // flatMap
    // -----------------------------------------------------------------------

    @Test
    void flatMapChainsRightValues() {
        Either<String, Integer> e = Either.right(5);
        Either<String, Integer> result = e.flatMap(n -> Either.right(n * 2));

        assertInstanceOf(Either.Right.class, result);
        assertEquals(10, ((Either.Right<String, Integer>) result).value());
    }

    @Test
    void flatMapShortCircuitsOnLeft() {
        Either<String, Integer> e = Either.right(5);
        Either<String, Integer> result = e.flatMap(__ -> Either.left("fail"));

        assertInstanceOf(Either.Left.class, result);
        assertEquals("fail", ((Either.Left<String, Integer>) result).value());
    }

    @Test
    void flatMapDoesNotRunOnLeft() {
        Either<String, Integer> e = Either.left("already left");
        Either<String, Integer> result = e.flatMap(n -> Either.right(n * 2));

        assertInstanceOf(Either.Left.class, result);
        assertEquals("already left", ((Either.Left<String, Integer>) result).value());
    }

    // -----------------------------------------------------------------------
    // mapLeft
    // -----------------------------------------------------------------------

    @Test
    void mapLeftTransformsLeftValue() {
        Either<String, Integer> e = Either.left("error");
        Either<Integer, Integer> mapped = e.mapLeft(String::length);

        assertInstanceOf(Either.Left.class, mapped);
        assertEquals(5, ((Either.Left<Integer, Integer>) mapped).value());
    }

    @Test
    void mapLeftDoesNotAffectRight() {
        Either<String, Integer> e = Either.right(42);
        Either<Integer, Integer> mapped = e.mapLeft(String::length);

        assertInstanceOf(Either.Right.class, mapped);
        assertEquals(42, ((Either.Right<Integer, Integer>) mapped).value());
    }

    // -----------------------------------------------------------------------
    // fold
    // -----------------------------------------------------------------------

    @Test
    void foldAppliesLeftFunctionOnLeft() {
        Either<String, Integer> e = Either.left("fail");
        String result = e.fold(l -> "Error: " + l, r -> "Value: " + r);

        assertEquals("Error: fail", result);
    }

    @Test
    void foldAppliesRightFunctionOnRight() {
        Either<String, Integer> e = Either.right(42);
        String result = e.fold(l -> "Error: " + l, r -> "Value: " + r);

        assertEquals("Value: 42", result);
    }

    // -----------------------------------------------------------------------
    // getOrElse
    // -----------------------------------------------------------------------

    @Test
    void getOrElseDefaultReturnsValueOnRight() {
        Either<String, Integer> e = Either.right(5);
        assertEquals(5, e.getOrElse(99));
    }

    @Test
    void getOrElseDefaultReturnsDefaultOnLeft() {
        Either<String, Integer> e = Either.left("error");
        assertEquals(99, e.getOrElse(99));
    }

    @Test
    void getOrElseFunctionComputesFromLeft() {
        Either<String, Integer> e = Either.left("five");
        assertEquals(4, e.getOrElse(String::length));
    }

    @Test
    void getOrElseFunctionReturnsValueOnRight() {
        Either<String, Integer> e = Either.right(42);
        assertEquals(42, e.getOrElse(String::length));
    }

    // -----------------------------------------------------------------------
    // swap
    // -----------------------------------------------------------------------

    @Test
    void swapFlipsLeftToRight() {
        Either<String, Integer> e = Either.left("hello");
        Either<Integer, String> swapped = e.swap();

        assertInstanceOf(Either.Right.class, swapped);
        assertEquals("hello", ((Either.Right<Integer, String>) swapped).value());
    }

    @Test
    void swapFlipsRightToLeft() {
        Either<String, Integer> e = Either.right(42);
        Either<Integer, String> swapped = e.swap();

        assertInstanceOf(Either.Left.class, swapped);
        assertEquals(42, ((Either.Left<Integer, String>) swapped).value());
    }
}
