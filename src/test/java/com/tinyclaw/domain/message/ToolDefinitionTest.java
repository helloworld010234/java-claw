package com.tinyclaw.domain.message;

import com.tinyclaw.domain.common.TinyClawDomainException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolDefinitionTest {

    @Test
    void validDefinition() {
        ToolDefinition def = new ToolDefinition("read_file", "Read a file", "{\"type\":\"object\"}");
        assertThat(def.name()).isEqualTo("read_file");
        assertThat(def.description()).isEqualTo("Read a file");
        assertThat(def.inputSchemaJson()).isEqualTo("{\"type\":\"object\"}");
    }

    @Test
    void nullNameThrows() {
        assertThatThrownBy(() -> new ToolDefinition(null, "desc", "{}"))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("name");
    }

    @Test
    void blankNameThrows() {
        assertThatThrownBy(() -> new ToolDefinition("", "desc", "{}"))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("name");
    }

    @Test
    void nullDescriptionThrows() {
        assertThatThrownBy(() -> new ToolDefinition("read_file", null, "{}"))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("description");
    }

    @Test
    void nullSchemaThrows() {
        assertThatThrownBy(() -> new ToolDefinition("read_file", "desc", null))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("inputSchemaJson");
    }
}
