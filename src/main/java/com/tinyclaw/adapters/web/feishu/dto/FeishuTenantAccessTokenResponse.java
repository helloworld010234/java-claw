package com.tinyclaw.adapters.web.feishu.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response body from Feishu tenant_access_token/internal endpoint.
 *
 * @param code               Feishu business code, 0 means success
 * @param msg                Feishu message
 * @param tenantAccessToken  the token to use for subsequent calls
 * @param expire             token lifetime in seconds
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FeishuTenantAccessTokenResponse(
    @JsonProperty("code") int code,
    @JsonProperty("msg") String msg,
    @JsonProperty("tenant_access_token") String tenantAccessToken,
    @JsonProperty("expire") int expire
) {
}
