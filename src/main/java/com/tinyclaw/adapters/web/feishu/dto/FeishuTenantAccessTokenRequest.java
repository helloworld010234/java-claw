package com.tinyclaw.adapters.web.feishu.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for Feishu tenant_access_token/internal endpoint.
 *
 * @param appId     Feishu app ID
 * @param appSecret Feishu app secret
 */
public record FeishuTenantAccessTokenRequest(
    @JsonProperty("app_id") String appId,
    @JsonProperty("app_secret") String appSecret
) {
}
