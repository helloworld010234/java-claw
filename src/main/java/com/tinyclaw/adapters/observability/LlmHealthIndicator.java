package com.tinyclaw.adapters.observability;

import com.tinyclaw.ports.llm.LlmGateway;
import com.tinyclaw.ports.llm.LlmRequest;
import com.tinyclaw.ports.llm.LlmRequestOptions;
import com.tinyclaw.ports.llm.LlmResponse;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * LLM 连通性 Health Check。
 */
@Component
public class LlmHealthIndicator implements HealthIndicator {

    private final LlmGateway llmGateway;

    public LlmHealthIndicator(LlmGateway llmGateway) {
        this.llmGateway = llmGateway;
    }

    @Override
    public Health health() {
        try {
            LlmRequest pingRequest = new LlmRequest(
                "",
                List.of(),
                List.of(),
                LlmRequestOptions.defaults()
            );
            llmGateway.generate(pingRequest);
            return Health.up().build();
        } catch (Exception e) {
            return Health.down()
                .withDetail("error", e.getMessage())
                .build();
        }
    }
}
