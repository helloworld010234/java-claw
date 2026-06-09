package com.tinyclaw.domain.message;

import com.tinyclaw.domain.common.TinyClawDomainException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoleTest {

    @Test
    void fromWireValueSystemReturnsSystem() {
        assertThat(Role.fromWireValue("system")).isEqualTo(Role.SYSTEM);
    }

    @Test
    void fromWireValueUserReturnsUser() {
        assertThat(Role.fromWireValue("user")).isEqualTo(Role.USER);
    }

    @Test
    void fromWireValueAssistantReturnsAssistant() {
        assertThat(Role.fromWireValue("assistant")).isEqualTo(Role.ASSISTANT);
    }

    @Test
    void fromWireValueIsCaseInsensitive() {
        assertThat(Role.fromWireValue("SYSTEM")).isEqualTo(Role.SYSTEM);
        assertThat(Role.fromWireValue("User")).isEqualTo(Role.USER);
        assertThat(Role.fromWireValue("Assistant")).isEqualTo(Role.ASSISTANT);
    }

    @Test
    void fromWireValueNullThrows() {
        assertThatThrownBy(() -> Role.fromWireValue(null))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("must not be null");
    }

    @Test
    void fromWireValueUnknownThrows() {
        assertThatThrownBy(() -> Role.fromWireValue("developer"))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("Unknown Role wire value");
    }

    @Test
    void wireValueReturnsLowercase() {
        assertThat(Role.SYSTEM.wireValue()).isEqualTo("system");
        assertThat(Role.USER.wireValue()).isEqualTo("user");
        assertThat(Role.ASSISTANT.wireValue()).isEqualTo("assistant");
    }
}
