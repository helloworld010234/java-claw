package com.tinyclaw.application.approval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalArgumentPreviewerTest {

    private final ApprovalArgumentPreviewer previewer = new ApprovalArgumentPreviewer();

    @Test
    void nullReturnsEmptyString() {
        assertThat(previewer.preview(null)).isEmpty();
    }

    @Test
    void shortContentUnchanged() {
        String json = "{\"command\":\"echo hello\"}";
        assertThat(previewer.preview(json)).isEqualTo(json);
    }

    @Test
    void exactlyMaxLengthUnchanged() {
        String content = "a".repeat(1000);
        assertThat(previewer.preview(content)).hasSize(1000).isEqualTo(content);
    }

    @Test
    void overMaxLengthTruncatedWithSuffix() {
        String content = "b".repeat(1001);
        String result = previewer.preview(content);
        assertThat(result).endsWith("... [truncated]");
        assertThat(result).hasSize(1000);
    }

    @Test
    void veryLongContentTruncated() {
        String content = "x".repeat(5000);
        String result = previewer.preview(content);
        assertThat(result).endsWith("... [truncated]");
        assertThat(result).hasSize(1000);
    }
}
