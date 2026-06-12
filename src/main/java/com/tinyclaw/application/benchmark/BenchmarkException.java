package com.tinyclaw.application.benchmark;

/**
 * Runtime exception thrown when benchmark infrastructure fails.
 */
public class BenchmarkException extends RuntimeException {

    public BenchmarkException(String message, Throwable cause) {
        super(message, cause);
    }
}
