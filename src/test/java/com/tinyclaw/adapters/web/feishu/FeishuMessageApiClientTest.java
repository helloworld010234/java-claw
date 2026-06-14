package com.tinyclaw.adapters.web.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeishuMessageApiClientTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void sendTextMessageSuccessReturnsMessageId() {
        FakeTransport transport = new FakeTransport("msg-123");
        FeishuMessageApiClient client = new FeishuMessageApiClient("https://open.feishu.cn", transport, OBJECT_MAPPER);

        String messageId = client.sendTextMessage("chat-1", "hello", "token-1");

        assertThat(messageId).isEqualTo("msg-123");
        assertThat(transport.lastUri).contains("/open-apis/im/v1/messages?receive_id_type=chat_id");
        assertThat(transport.lastHeaders).containsEntry("Authorization", "Bearer token-1");
    }

    @Test
    void sendTextMessageWithNullDataReturnsNull() {
        FeishuHttpTransport transport = new FakeTransportWithNullData();
        FeishuMessageApiClient client = new FeishuMessageApiClient("https://open.feishu.cn", transport, OBJECT_MAPPER);

        String messageId = client.sendTextMessage("chat-1", "hello", "token-1");

        assertThat(messageId).isNull();
    }

    @Test
    void sendTextMessageThrowsOnFeishuError() {
        FakeTransport transport = new FakeTransport(99991670, "bad request");
        FeishuMessageApiClient client = new FeishuMessageApiClient("https://open.feishu.cn", transport, OBJECT_MAPPER);

        assertThatThrownBy(() -> client.sendTextMessage("chat-1", "hello", "token-1"))
            .isInstanceOf(FeishuApiException.class)
            .satisfies(e -> assertThat(((FeishuApiException) e).getCode()).isEqualTo(99991670));
    }

    @Test
    void sendTextMessageValidatesInputs() {
        FakeTransport transport = new FakeTransport("msg-1");
        FeishuMessageApiClient client = new FeishuMessageApiClient("https://open.feishu.cn", transport, OBJECT_MAPPER);

        assertThatThrownBy(() -> client.sendTextMessage(null, "hello", "token"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.sendTextMessage("chat", null, "token"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.sendTextMessage("chat", "hello", null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.sendTextMessage("chat", "hello", "  "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullBaseUrlRejected() {
        assertThatThrownBy(() -> new FeishuMessageApiClient(null, new FakeTransport("msg-1"), OBJECT_MAPPER))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullTransportRejected() {
        assertThatThrownBy(() -> new FeishuMessageApiClient("https://open.feishu.cn", null, OBJECT_MAPPER))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullObjectMapperRejected() {
        assertThatThrownBy(() -> new FeishuMessageApiClient("https://open.feishu.cn", new FakeTransport("msg-1"), null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void baseUrlWithTrailingSlashIsNormalized() {
        FakeTransport transport = new FakeTransport("msg-1");
        FeishuMessageApiClient client = new FeishuMessageApiClient("https://open.feishu.cn/", transport, OBJECT_MAPPER);

        client.sendTextMessage("chat-1", "hello", "token-1");

        assertThat(transport.lastUri).isEqualTo("https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=chat_id");
    }

    @Test
    void parseErrorThrowsSafely() {
        FeishuHttpTransport transport = new FakeTransportWithRawBody("not-json");
        FeishuMessageApiClient client = new FeishuMessageApiClient("https://open.feishu.cn", transport, OBJECT_MAPPER);

        assertThatThrownBy(() -> client.sendTextMessage("chat-1", "hello", "token-1"))
            .isInstanceOf(FeishuApiException.class)
            .hasMessageContaining("parse");
    }

    @Test
    void serializeErrorThrowsSafely() {
        ObjectMapper brokenMapper = new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) throws com.fasterxml.jackson.core.JsonProcessingException {
                throw new com.fasterxml.jackson.core.JsonProcessingException("boom") {};
            }
        };
        FakeTransport transport = new FakeTransport("msg-1");
        FeishuMessageApiClient client = new FeishuMessageApiClient("https://open.feishu.cn", transport, brokenMapper);

        assertThatThrownBy(() -> client.sendTextMessage("chat-1", "hello", "token-1"))
            .isInstanceOf(FeishuApiException.class)
            .hasMessageContaining("serialize");
    }

    private static class FakeTransport implements FeishuHttpTransport {

        private final String successMessageId;
        private final Integer errorCode;
        private final String errorMsg;

        String lastUri;
        Map<String, String> lastHeaders;

        FakeTransport(String successMessageId) {
            this(successMessageId, null, null);
        }

        FakeTransport(Integer errorCode, String errorMsg) {
            this(null, errorCode, errorMsg);
        }

        FakeTransport(String successMessageId, Integer errorCode, String errorMsg) {
            this.successMessageId = successMessageId;
            this.errorCode = errorCode;
            this.errorMsg = errorMsg;
        }

        @Override
        public String post(String uri, Map<String, String> headers, Object body) {
            this.lastUri = uri;
            this.lastHeaders = headers;
            try {
                if (errorCode != null) {
                    return OBJECT_MAPPER.writeValueAsString(Map.of("code", errorCode, "msg", errorMsg));
                }
                return OBJECT_MAPPER.writeValueAsString(Map.of(
                    "code", 0,
                    "msg", "ok",
                    "data", Map.of("message_id", successMessageId)
                ));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static class FakeTransportWithNullData implements FeishuHttpTransport {
        @Override
        public String post(String uri, Map<String, String> headers, Object body) {
            try {
                return OBJECT_MAPPER.writeValueAsString(Map.of("code", 0, "msg", "ok"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static class FakeTransportWithRawBody implements FeishuHttpTransport {
        private final String body;

        FakeTransportWithRawBody(String body) {
            this.body = body;
        }

        @Override
        public String post(String uri, Map<String, String> headers, Object body) {
            return this.body;
        }
    }
}
