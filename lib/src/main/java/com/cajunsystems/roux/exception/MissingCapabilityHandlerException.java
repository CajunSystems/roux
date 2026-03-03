package com.cajunsystems.roux.exception;

public class MissingCapabilityHandlerException extends IllegalStateException {
    public MissingCapabilityHandlerException(String message) {
        super(message);
    }

    public MissingCapabilityHandlerException(String message, Throwable cause) {
        super(message, cause);
    }
}
