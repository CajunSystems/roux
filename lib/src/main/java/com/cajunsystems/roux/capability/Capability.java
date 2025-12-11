package com.cajunsystems.roux.capability;

import com.cajunsystems.roux.Effect;

public interface Capability<R> {
    
    default <E extends Throwable> Effect<E, R> toEffect() {
        return Effect.from(this);
    }
}
