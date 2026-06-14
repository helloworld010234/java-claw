package com.tinyclaw.adapters.web.feishu.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response body from Feishu send message endpoint.
 *
 * @param code Feishu business code, 0 means success
 * @param msg  Feishu message
 * @param data message metadata including the created message ID
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FeishuSendMessageResponse(
    @JsonProperty("code") int code,
    @JsonProperty("msg") String msg,
    @JsonProperty("data") FeishuSendMessageData data
) {
}
