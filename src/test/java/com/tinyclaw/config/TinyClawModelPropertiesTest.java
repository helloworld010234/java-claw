package com.tinyclaw.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TinyClawModelPropertiesTest {

    @Test
    void hasSensibleDefaults() {
        TinyClawModelProperties props = new TinyClawModelProperties();

        assertThat(props.isEnabled()).isFalse();
        assertThat(props.getProvider()).isEqualTo("spring-ai");
        assertThat(props.getName()).isEqualTo("deepseek-v4-flash");
        assertThat(props.getBaseUrl()).isEmpty();
        assertThat(props.getApiKey()).isEmpty();
        assertThat(props.getTemperature()).isEqualTo(0.7);
        assertThat(props.getMaxTokens()).isEqualTo(4096);
        assertThat(props.getPricing()).isNotNull();
        assertThat(props.getPricing().getInputPricePer1M()).isEqualTo(0.0);
        assertThat(props.getPricing().getOutputPricePer1M()).isEqualTo(0.0);

        // Reliability defaults
        assertThat(props.getRequestTimeoutSeconds()).isEqualTo(60);
        assertThat(props.getMaxRetryAttempts()).isEqualTo(3);
        assertThat(props.getRetryBackoffMs()).isEqualTo(1000);
        assertThat(props.isUsageCostSummaryEnabled()).isTrue();
    }

    @Test
    void acceptsCustomValues() {
        TinyClawModelProperties props = new TinyClawModelProperties();
        props.setEnabled(true);
        props.setApiKey("sk-test");
        props.setBaseUrl("https://example.com");
        props.setName("gpt-4");
        props.setTemperature(0.5);
        props.setMaxTokens(2048);
        props.getPricing().setInputPricePer1M(0.15);
        props.getPricing().setOutputPricePer1M(0.15);
        props.setRequestTimeoutSeconds(120);
        props.setMaxRetryAttempts(5);
        props.setRetryBackoffMs(2000);
        props.setUsageCostSummaryEnabled(false);

        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getApiKey()).isEqualTo("sk-test");
        assertThat(props.getBaseUrl()).isEqualTo("https://example.com");
        assertThat(props.getName()).isEqualTo("gpt-4");
        assertThat(props.getTemperature()).isEqualTo(0.5);
        assertThat(props.getMaxTokens()).isEqualTo(2048);
        assertThat(props.getPricing().getInputPricePer1M()).isEqualTo(0.15);
        assertThat(props.getPricing().getOutputPricePer1M()).isEqualTo(0.15);
        assertThat(props.getRequestTimeoutSeconds()).isEqualTo(120);
        assertThat(props.getMaxRetryAttempts()).isEqualTo(5);
        assertThat(props.getRetryBackoffMs()).isEqualTo(2000);
        assertThat(props.isUsageCostSummaryEnabled()).isFalse();
    }

    @Test
    void supportsDeepseekV4FlashModelName() {
        TinyClawModelProperties props = new TinyClawModelProperties();
        props.setName("deepseek-v4-flash");
        assertThat(props.getName()).isEqualTo("deepseek-v4-flash");
    }

    @Test
    void pricingSetterHandlesNull() {
        TinyClawModelProperties props = new TinyClawModelProperties();
        props.setPricing(null);
        assertThat(props.getPricing()).isNotNull();
    }
}
