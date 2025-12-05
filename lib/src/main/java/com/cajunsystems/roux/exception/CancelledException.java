package com.cajunsystems.roux.exception;

public class CancelledException extends RuntimeException {
    public CancelledException() {
        super("Effect was cancelled");
    }

    public CancelledException(InterruptedException cause) {
        super("Effect was cancelled due to interruption", cause);
    }
}
