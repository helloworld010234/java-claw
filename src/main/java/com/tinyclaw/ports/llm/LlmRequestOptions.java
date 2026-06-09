package com.tinyclaw.ports.llm;

/**
 * Generation options for an LLM request.
 *
 * @param maxTokens     maximum tokens to generate
 * @param temperature   sampling temperature (0.0 = deterministic)
 */
public record LlmRequestOptions(int maxTokens, double temperature) {

    private static final int DEFAULT_MAX_TOKENS = 4096;
    private static final double DEFAULT_TEMPERATURE = 0.7;

    public LlmRequestOptions {
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
        if (temperature < 0.0 || temperature > 2.0) {
            throw new IllegalArgumentException("temperature must be between 0.0 and 2.0");
        }
    }

    public static LlmRequestOptions defaults() {
        return new LlmRequestOptions(DEFAULT_MAX_TOKENS, DEFAULT_TEMPERATURE);
    }
}
