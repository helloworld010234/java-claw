package com.tinyclaw.adapters.llm;

import com.tinyclaw.config.TinyClawModelProperties;
import com.tinyclaw.domain.message.Usage;
import com.tinyclaw.ports.llm.LlmException;
import com.tinyclaw.ports.llm.LlmGateway;
import com.tinyclaw.ports.llm.LlmRequest;
import com.tinyclaw.ports.llm.LlmResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;

/**
 * Decorator around an {@link LlmGateway} that records usage, latency, and estimated cost metrics.
 *
 * <p>Delegates the actual LLM call and then publishes Micrometer metrics. If the delegate throws,
 * a failure metric is recorded and the exception is re-thrown wrapped in {@link LlmException}.</p>
 */
public class ObservedLlmGateway implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(ObservedLlmGateway.class);

    private final LlmGateway delegate;
    private final MeterRegistry meterRegistry;
    private final TinyClawModelProperties properties;

    public ObservedLlmGateway(LlmGateway delegate, MeterRegistry meterRegistry, TinyClawModelProperties properties) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        if (meterRegistry == null) {
            throw new IllegalArgumentException("meterRegistry must not be null");
        }
        this.delegate = delegate;
        this.meterRegistry = meterRegistry;
        this.properties = properties != null ? properties : new TinyClawModelProperties();
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        Instant start = Instant.now();
        boolean success = false;
        LlmResponse response = null;

        try {
            response = delegate.generate(request);
            success = true;
            return response;
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("LLM call failed: " + e.getMessage(), e);
        } finally {
            long latencyMs = Duration.between(start, Instant.now()).toMillis();
            recordMetrics(request, response, latencyMs, success);
        }
    }

    private void recordMetrics(LlmRequest request, LlmResponse response, long latencyMs, boolean success) {
        String model = request.model() != null ? request.model() : "unknown";
        Tags tags = Tags.of("model", model, "status", success ? "success" : "failure");

        try {
            meterRegistry.counter("tinyclaw.llm.requests", tags).increment();
            meterRegistry.timer("tinyclaw.llm.latency", tags).record(latencyMs, java.util.concurrent.TimeUnit.MILLISECONDS);

            if (response != null && response.usage() != null) {
                Usage usage = response.usage();
                meterRegistry.counter("tinyclaw.llm.tokens", tags.and("type", "prompt")).increment(usage.promptTokens());
                meterRegistry.counter("tinyclaw.llm.tokens", tags.and("type", "completion")).increment(usage.completionTokens());

                double cost = estimateCost(usage);
                if (cost > 0) {
                    meterRegistry.counter("tinyclaw.llm.estimated.cost", tags).increment(cost);
                }
            }
        } catch (Exception metricEx) {
            log.warn("Failed to record LLM metrics: {}", metricEx.getMessage());
        }
    }

    private double estimateCost(Usage usage) {
        if (properties.getPricing() == null) {
            return 0.0;
        }
        TinyClawModelProperties.Pricing pricing = properties.getPricing();
        return (usage.promptTokens() * pricing.getInputPricePer1M()
                + usage.completionTokens() * pricing.getOutputPricePer1M()) / 1_000_000.0;
    }
}
