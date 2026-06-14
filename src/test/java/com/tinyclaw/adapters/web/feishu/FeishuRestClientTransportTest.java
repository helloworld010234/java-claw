package com.tinyclaw.adapters.web.feishu;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FeishuRestClientTransportTest {

    @Test
    void postReturnsResponseBody() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        RestClient restClient = RestClient.builder(restTemplate).build();
        FeishuRestClientTransport transport = new FeishuRestClientTransport(restClient);

        server.expect(requestTo("https://open.feishu.cn/test"))
            .andRespond(withSuccess("{\"code\":0}", MediaType.APPLICATION_JSON));

        String response = transport.post("https://open.feishu.cn/test", Map.of("X-Custom", "v"), Map.of("key", "value"));

        assertThat(response).isEqualTo("{\"code\":0}");
        server.verify();
    }

    @Test
    void postThrowsOnHttpError() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        RestClient restClient = RestClient.builder(restTemplate).build();
        FeishuRestClientTransport transport = new FeishuRestClientTransport(restClient);

        server.expect(requestTo("https://open.feishu.cn/test"))
            .andRespond(withBadRequest().body("{\"code\":1}").contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> transport.post("https://open.feishu.cn/test", Map.of(), Map.of("key", "value")))
            .isInstanceOf(FeishuApiException.class)
            .hasMessageContaining("HTTP error");
        server.verify();
    }

    @Test
    void postThrowsOnInvalidArguments() {
        RestTemplate restTemplate = new RestTemplate();
        RestClient restClient = RestClient.builder(restTemplate).build();
        FeishuRestClientTransport transport = new FeishuRestClientTransport(restClient);

        assertThatThrownBy(() -> transport.post(null, Map.of(), Map.of("key", "value")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transport.post("https://example.com", null, Map.of("key", "value")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transport.post("https://example.com", Map.of(), null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullRestClientRejected() {
        assertThatThrownBy(() -> new FeishuRestClientTransport(null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
