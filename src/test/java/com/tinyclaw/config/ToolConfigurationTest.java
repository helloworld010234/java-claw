package com.tinyclaw.config;

import com.tinyclaw.application.tool.ToolRegistry;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolResult;
import com.tinyclaw.ports.tool.ToolExecutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ToolConfigurationTest {

    @Autowired
    private ToolRegistry toolRegistry;

    @Test
    void readFileToolIsRegistered() {
        assertThat(toolRegistry.find("read_file")).isPresent();
    }

    @Test
    void writeFileToolIsRegistered() {
        assertThat(toolRegistry.find("write_file")).isPresent();
    }

    @Test
    void editFileToolIsRegistered() {
        assertThat(toolRegistry.find("edit_file")).isPresent();
    }

    @Test
    void shellCommandToolIsRegistered() {
        assertThat(toolRegistry.find("shell_command")).isPresent();
    }

    @Test
    void editFileExecutionWorksThroughRegistry(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("notes.txt"), "hello world");

        ToolCall call = ToolCall.of(
            "call-1",
            "edit_file",
            "{\"path\":\"notes.txt\",\"oldText\":\"world\",\"newText\":\"agent\"}"
        );
        ToolExecutionContext context = new ToolExecutionContext(workspace);

        ToolResult result = toolRegistry.execute(call, context);

        assertThat(result.error()).isFalse();
        assertThat(Files.readString(workspace.resolve("notes.txt"))).isEqualTo("hello agent");
    }
}
