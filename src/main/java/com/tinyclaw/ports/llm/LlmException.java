package com.tinyclaw.ports.llm;

/**
 * Exception thrown when the LLM gateway fails to produce a response.
 */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
