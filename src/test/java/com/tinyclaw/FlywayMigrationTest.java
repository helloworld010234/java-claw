package com.tinyclaw;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway Migration 验证测试。
 *
 * <p>使用 H2 内存数据库，确认 V1 migration 成功创建了全部 7 张核心表。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class FlywayMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void allRequiredTablesShouldExist() {
        List<String> tables = jdbcTemplate.queryForList(
            "SELECT UPPER(TABLE_NAME) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'",
            String.class
        );

        assertThat(tables)
            .contains(
                "AGENT_SESSIONS",
                "AGENT_MESSAGES",
                "AGENT_RUNS",
                "TOOL_EXECUTIONS",
                "APPROVAL_REQUESTS",
                "TRACE_SPANS",
                "USAGE_RECORDS"
            );
    }

    @Test
    void auditColumnsShouldExist() {
        List<String> runColumns = jdbcTemplate.queryForList(
            "SELECT UPPER(COLUMN_NAME) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'AGENT_RUNS'",
            String.class
        );
        assertThat(runColumns).contains("MODE", "PROMPT", "COMPLETED_AT");

        List<String> toolColumns = jdbcTemplate.queryForList(
            "SELECT UPPER(COLUMN_NAME) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'TOOL_EXECUTIONS'",
            String.class
        );
        assertThat(toolColumns).contains("STARTED_AT", "COMPLETED_AT");
    }
}
