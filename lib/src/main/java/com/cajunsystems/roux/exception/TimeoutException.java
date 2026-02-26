package com.cajunsystems.roux.exception;

import java.time.Duration;

/**
 * Thrown when an effect does not complete within its specified timeout duration.
 */
public class TimeoutException extends RuntimeException {
    private final Duration duration;

    public TimeoutException(Duration duration) {
        super("Effect timed out after " + duration);
        this.duration = duration;
    }

    public Duration getDuration() {
        return duration;
    }
}
