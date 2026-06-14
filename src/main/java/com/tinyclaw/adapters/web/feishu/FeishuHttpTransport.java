package com.tinyclaw.adapters.web.feishu;

import java.util.Map;

/**
 * Minimal HTTP transport abstraction for Feishu OpenAPI calls.
 *
 * <p>Exists to keep the token provider and message sender unit-testable with a
 * fake transport, avoiding real network calls in tests.</p>
 */
public interface FeishuHttpTransport {

    /**
     * POST a JSON request body and return the raw JSON response body.
     *
     * @param uri     full request URI (including query string if any)
     * @param headers HTTP headers to add; may be empty but not null
     * @param body    request body object; will be serialized to JSON by the transport
     * @return raw response body string
     * @throws FeishuApiException if the HTTP call fails or returns a non-2xx status
     */
    String post(String uri, Map<String, String> headers, Object body);
}
