package com.tinyclaw.ports.llm;

/**
 * Exception thrown when the LLM gateway fails to produce a response.
 */
public class LlmException extends RuntimeException {

    private final LlmErrorType errorType;

    public LlmException(String message) {
        this(message, LlmErrorType.UNKNOWN);
    }

    public LlmException(String message, Throwable cause) {
        this(message, cause, LlmErrorType.UNKNOWN);
    }

    public LlmException(String message, LlmErrorType errorType) {
        super(message);
        this.errorType = errorType != null ? errorType : LlmErrorType.UNKNOWN;
    }

    public LlmException(String message, Throwable cause, LlmErrorType errorType) {
        super(message, cause);
        this.errorType = errorType != null ? errorType : LlmErrorType.UNKNOWN;
    }

    /**
     * Returns the categorised error type. Never null.
     */
    public LlmErrorType getErrorType() {
        return errorType;
    }
}
