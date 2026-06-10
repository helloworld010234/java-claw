package com.tinyclaw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for LLM model integration.
 *
 * <p>All real-LLM settings are prefixed with {@code tiny-claw.model}.</p>
 */
@ConfigurationProperties(prefix = "tiny-claw.model")
public class TinyClawModelProperties {

    private boolean enabled = false;
    private String provider = "spring-ai";
    private String name = "glm-4.5-air";
    private String baseUrl = "";
    private String apiKey = "";
    private double temperature = 0.7;
    private int maxTokens = 4096;
    private Pricing pricing = new Pricing();

    // Reliability settings
    private int requestTimeoutSeconds = 60;
    private int maxRetryAttempts = 3;
    private long retryBackoffMs = 1000;
    private boolean usageCostSummaryEnabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Pricing getPricing() {
        return pricing;
    }

    public void setPricing(Pricing pricing) {
        this.pricing = pricing != null ? pricing : new Pricing();
    }

    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
        this.requestTimeoutSeconds = requestTimeoutSeconds;
    }

    public int getMaxRetryAttempts() {
        return maxRetryAttempts;
    }

    public void setMaxRetryAttempts(int maxRetryAttempts) {
        this.maxRetryAttempts = maxRetryAttempts;
    }

    public long getRetryBackoffMs() {
        return retryBackoffMs;
    }

    public void setRetryBackoffMs(long retryBackoffMs) {
        this.retryBackoffMs = retryBackoffMs;
    }

    public boolean isUsageCostSummaryEnabled() {
        return usageCostSummaryEnabled;
    }

    public void setUsageCostSummaryEnabled(boolean usageCostSummaryEnabled) {
        this.usageCostSummaryEnabled = usageCostSummaryEnabled;
    }

    /**
     * Pricing configuration for cost estimation.
     */
    public static class Pricing {
        private double inputPricePer1M = 0.0;
        private double outputPricePer1M = 0.0;

        public double getInputPricePer1M() {
            return inputPricePer1M;
        }

        public void setInputPricePer1M(double inputPricePer1M) {
            this.inputPricePer1M = inputPricePer1M;
        }

        public double getOutputPricePer1M() {
            return outputPricePer1M;
        }

        public void setOutputPricePer1M(double outputPricePer1M) {
            this.outputPricePer1M = outputPricePer1M;
        }
    }
}
