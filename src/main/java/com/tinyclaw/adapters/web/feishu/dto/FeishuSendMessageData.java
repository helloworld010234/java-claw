package com.tinyclaw.adapters.web.feishu.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Data payload inside a Feishu send-message response.
 *
 * @param messageId the ID of the sent message
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FeishuSendMessageData(
    @JsonProperty("message_id") String messageId
) {
}
