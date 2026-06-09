package com.tinyclaw.domain.message;

import com.tinyclaw.domain.common.TinyClawDomainException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UsageTest {

    @Test
    void totalTokensReturnsSum() {
        Usage usage = new Usage(10, 20);
        assertThat(usage.totalTokens()).isEqualTo(30);
    }

    @Test
    void zeroTokensIsValid() {
        Usage usage = new Usage(0, 0);
        assertThat(usage.totalTokens()).isZero();
    }

    @Test
    void negativePromptTokensThrows() {
        assertThatThrownBy(() -> new Usage(-1, 10))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("promptTokens");
    }

    @Test
    void negativeCompletionTokensThrows() {
        assertThatThrownBy(() -> new Usage(10, -1))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("completionTokens");
    }
}
