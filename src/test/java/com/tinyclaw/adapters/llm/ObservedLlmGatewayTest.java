package com.tinyclaw.adapters.llm;

import com.tinyclaw.config.TinyClawModelProperties;
import com.tinyclaw.domain.message.Usage;
import com.tinyclaw.ports.llm.LlmException;
import com.tinyclaw.ports.llm.LlmGateway;
import com.tinyclaw.ports.llm.LlmRequest;
import com.tinyclaw.ports.llm.LlmResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ObservedLlmGatewayTest {

    private final LlmGateway delegate = mock(LlmGateway.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final TinyClawModelProperties properties = new TinyClawModelProperties();

    private ObservedLlmGateway createGateway() {
        return new ObservedLlmGateway(delegate, meterRegistry, properties);
    }

    @Test
    void recordsSuccessMetrics() {
        when(delegate.generate(any())).thenReturn(
            new LlmResponse("ok", List.of(), new Usage(10, 20))
        );

        ObservedLlmGateway gateway = createGateway();
        LlmRequest request = new LlmRequest("m", List.of(), List.of(), com.tinyclaw.ports.llm.LlmRequestOptions.defaults());
        gateway.generate(request);

        Counter requests = meterRegistry.find("tinyclaw.llm.requests").tags(Tags.of("model", "m", "status", "success")).counter();
        assertThat(requests).isNotNull();
        assertThat(requests.count()).isEqualTo(1.0);

        Counter promptTokens = meterRegistry.find("tinyclaw.llm.tokens").tags(Tags.of("model", "m", "status", "success", "type", "prompt")).counter();
        Counter completionTokens = meterRegistry.find("tinyclaw.llm.tokens").tags(Tags.of("model", "m", "status", "success", "type", "completion")).counter();
        assertThat(promptTokens).isNotNull();
        assertThat(promptTokens.count()).isEqualTo(10.0);
        assertThat(completionTokens).isNotNull();
        assertThat(completionTokens.count()).isEqualTo(20.0);
    }

    @Test
    void doesNotFailWhenUsageIsNull() {
        when(delegate.generate(any())).thenReturn(
            new LlmResponse("ok", List.of(), null)
        );

        ObservedLlmGateway gateway = createGateway();
        LlmRequest request = new LlmRequest("m", List.of(), List.of(), com.tinyclaw.ports.llm.LlmRequestOptions.defaults());
        LlmResponse response = gateway.generate(request);

        assertThat(response.usage()).isNull();
        Counter requests = meterRegistry.find("tinyclaw.llm.requests").tags(Tags.of("model", "m", "status", "success")).counter();
        assertThat(requests).isNotNull();
        assertThat(requests.count()).isEqualTo(1.0);
    }

    @Test
    void recordsFailureMetricsAndRethrows() {
        when(delegate.generate(any())).thenThrow(new RuntimeException("boom"));

        ObservedLlmGateway gateway = createGateway();
        LlmRequest request = new LlmRequest("m", List.of(), List.of(), com.tinyclaw.ports.llm.LlmRequestOptions.defaults());

        assertThatThrownBy(() -> gateway.generate(request))
            .isInstanceOf(LlmException.class)
            .hasMessageContaining("boom");

        Counter requests = meterRegistry.find("tinyclaw.llm.requests").tags(Tags.of("model", "m", "status", "failure")).counter();
        assertThat(requests).isNotNull();
        assertThat(requests.count()).isEqualTo(1.0);
    }

    @Test
    void recordsCostWhenPricingConfigured() {
        properties.setPricing(new TinyClawModelProperties.Pricing());
        properties.getPricing().setInputPricePer1M(1.0);
        properties.getPricing().setOutputPricePer1M(2.0);

        when(delegate.generate(any())).thenReturn(
            new LlmResponse("ok", List.of(), new Usage(1000, 500))
        );

        ObservedLlmGateway gateway = createGateway();
        LlmRequest request = new LlmRequest("m", List.of(), List.of(), com.tinyclaw.ports.llm.LlmRequestOptions.defaults());
        gateway.generate(request);

        Counter costCounter = meterRegistry.find("tinyclaw.llm.estimated.cost").tags(Tags.of("model", "m", "status", "success")).counter();
        assertThat(costCounter).isNotNull();
        double expected = (1000 * 1.0 + 500 * 2.0) / 1_000_000.0;
        assertThat(costCounter.count()).isEqualTo(expected);
    }

    @Test
    void constructorRejectsNullDelegate() {
        assertThatThrownBy(() -> new ObservedLlmGateway(null, meterRegistry, properties))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("delegate");
    }

    @Test
    void constructorRejectsNullMeterRegistry() {
        assertThatThrownBy(() -> new ObservedLlmGateway(delegate, null, properties))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("meterRegistry");
    }
}
