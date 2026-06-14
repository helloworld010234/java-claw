package com.tinyclaw.adapters.web.feishu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Production {@link FeishuHttpTransport} implementation based on Spring
 * {@link RestClient}.
 *
 * <p>Serializes request bodies as JSON and returns raw JSON response bodies.
 * Does not log request or response bodies to avoid leaking secrets.</p>
 */
public class FeishuRestClientTransport implements FeishuHttpTransport {

    private static final Logger log = LoggerFactory.getLogger(FeishuRestClientTransport.class);

    private final RestClient restClient;

    /**
     * Creates the transport.
     *
     * @param restClient configured RestClient (timeouts should already be set)
     */
    public FeishuRestClientTransport(RestClient restClient) {
        if (restClient == null) {
            throw new IllegalArgumentException("restClient must not be null");
        }
        this.restClient = restClient;
    }

    @Override
    public String post(String uri, Map<String, String> headers, Object body) {
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("uri must not be blank");
        }
        if (headers == null) {
            throw new IllegalArgumentException("headers must not be null");
        }
        if (body == null) {
            throw new IllegalArgumentException("body must not be null");
        }
        try {
            log.debug("[FeishuTransport] POST {}", uri);
            return restClient.post()
                .uri(uri)
                .headers(hs -> headers.forEach(hs::add))
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new FeishuApiException("Feishu HTTP error: status=" + response.getStatusCode());
                })
                .body(String.class);
        } catch (FeishuApiException e) {
            throw e;
        } catch (Exception e) {
            throw new FeishuApiException("Feishu HTTP call failed", e);
        }
    }
}
