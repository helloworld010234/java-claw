package com.tinyclaw.adapters.web.feishu.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for Feishu send message endpoint.
 *
 * @param receiveId target chat ID
 * @param msgType   message type, e.g. "text"
 * @param content   JSON-string payload matching the message type schema
 */
public record FeishuSendMessageRequest(
    @JsonProperty("receive_id") String receiveId,
    @JsonProperty("msg_type") String msgType,
    @JsonProperty("content") String content
) {
}
