package com.tinyclaw.adapters.observability;

import com.tinyclaw.ports.llm.LlmException;
import com.tinyclaw.ports.llm.LlmGateway;
import com.tinyclaw.ports.llm.LlmRequest;
import com.tinyclaw.ports.llm.LlmResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Health Indicator 单元测试。
 *
 * <p>验证 {@link LlmHealthIndicator} 和 {@link DatabaseHealthIndicator}
 * 在上下游正常/异常时的健康状态报告。</p>
 */
class HealthIndicatorsTest {

    // ==================== LlmHealthIndicator ====================

    @Test
    void llmHealthIndicator_up_whenGatewayResponds() {
        LlmGateway gateway = request -> new LlmResponse("pong", List.of(), null);
        LlmHealthIndicator indicator = new LlmHealthIndicator(gateway);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void llmHealthIndicator_down_whenGatewayThrows() {
        LlmGateway gateway = request -> {
            throw new LlmException("LLM service unavailable");
        };
        LlmHealthIndicator indicator = new LlmHealthIndicator(gateway);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("error");
        assertThat(health.getDetails().get("error")).isEqualTo("LLM service unavailable");
    }

    // ==================== DatabaseHealthIndicator ====================

    @Test
    void databaseHealthIndicator_up_whenConnectionValid() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(5)).thenReturn(true);

        DatabaseHealthIndicator indicator = new DatabaseHealthIndicator(dataSource);
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void databaseHealthIndicator_down_whenConnectionInvalid() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(5)).thenReturn(false);

        DatabaseHealthIndicator indicator = new DatabaseHealthIndicator(dataSource);
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("reason")).isEqualTo("Connection not valid");
    }

    @Test
    void databaseHealthIndicator_down_whenSQLException() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("Connection refused"));

        DatabaseHealthIndicator indicator = new DatabaseHealthIndicator(dataSource);
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("error")).isEqualTo("Connection refused");
    }
}
