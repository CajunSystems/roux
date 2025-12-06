package com.cajunsystems.roux.data;

public record Unit() {
    private static final Unit INSTANCE = new Unit();

    public static Unit unit() {
        return INSTANCE;
    }
}