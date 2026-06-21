package travelcare_agent.agent.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeepSeekChatModelProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void rejectsMissingApiKeyWithSafeConfigurationError() {
        AgentProviderProperties properties = properties("http://127.0.0.1", "", 1000);
        DeepSeekChatModelProvider provider = new DeepSeekChatModelProvider(properties, objectMapper);

        assertThatThrownBy(() -> provider.call(request(1000)))
                .isInstanceOf(ModelCallException.class)
                .extracting(ex -> ((ModelCallException) ex).code())
                .isEqualTo("MODEL_API_KEY_MISSING");
    }

    @Test
    void mapsChatCompletionRequestAndResponse() throws Exception {
        AtomicReference<JsonNode> capturedBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        start(exchange -> {
            capturedBody.set(objectMapper.readTree(exchange.getRequestBody()));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, """
                    {"id":"chat-1","model":"deepseek-chat","choices":[{"message":{"content":"{\\"answer\\":\\"ok\\"}"},"finish_reason":"stop"}],"usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}}
                    """);
        });
        AgentProviderProperties properties = properties(baseUrl(), "test-key", 1000);
        DeepSeekChatModelProvider provider = new DeepSeekChatModelProvider(properties, objectMapper);

        ModelResponse response = provider.call(request(1000));

        assertThat(response.content()).isEqualTo("{\"answer\":\"ok\"}");
        assertThat(response.model()).isEqualTo("deepseek-chat");
        assertThat(response.provider()).isEqualTo("deepseek");
        assertThat(response.usage()).isEqualTo(new ModelUsage(7, 3, 10));
        assertThat(response.finishReason()).isEqualTo("stop");
        assertThat(response.latencyMs()).isNotNegative();
        assertThat(authorization.get()).isEqualTo("Bearer test-key");
        assertThat(capturedBody.get().path("model").asText()).isEqualTo("deepseek-chat");
        assertThat(capturedBody.get().path("messages").path(0).path("role").asText()).isEqualTo("user");
        assertThat(capturedBody.get().path("messages").path(0).path("content").asText()).isEqualTo("rendered prompt");
    }

    @Test
    void convertsEmptyContentToModelCallException() throws Exception {
        start(exchange -> respond(exchange, 200,
                "{\"model\":\"deepseek-chat\",\"choices\":[{\"message\":{\"content\":\"\"}}]}"));
        DeepSeekChatModelProvider provider = new DeepSeekChatModelProvider(
                properties(baseUrl(), "test-key", 1000), objectMapper
        );

        assertCode(provider, request(1000), "MODEL_EMPTY_RESPONSE");
    }

    @Test
    void convertsHttpFailureToModelCallException() throws Exception {
        start(exchange -> respond(exchange, 503, "{\"error\":{\"message\":\"upstream unavailable\"}}"));
        DeepSeekChatModelProvider provider = new DeepSeekChatModelProvider(
                properties(baseUrl(), "test-key", 1000), objectMapper
        );

        assertCode(provider, request(1000), "MODEL_HTTP_ERROR");
    }

    @Test
    void convertsMalformedBodyToModelCallException() throws Exception {
        start(exchange -> respond(exchange, 200, "not-json"));
        DeepSeekChatModelProvider provider = new DeepSeekChatModelProvider(
                properties(baseUrl(), "test-key", 1000), objectMapper
        );

        assertCode(provider, request(1000), "MODEL_INVALID_RESPONSE");
    }

    @Test
    void convertsReadTimeoutToModelCallException() throws Exception {
        start(exchange -> {
            try {
                Thread.sleep(250);
                respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        DeepSeekChatModelProvider provider = new DeepSeekChatModelProvider(
                properties(baseUrl(), "test-key", 25), objectMapper
        );

        assertCode(provider, request(25), "MODEL_TIMEOUT");
    }

    private void start(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static AgentProviderProperties properties(String baseUrl, String apiKey, int timeoutMs) {
        AgentProviderProperties properties = new AgentProviderProperties();
        properties.setProvider(AgentProviderType.DEEPSEEK);
        properties.setBaseUrl(baseUrl);
        properties.setApiKey(apiKey);
        properties.setModel("deepseek-chat");
        properties.setTimeoutMs(timeoutMs);
        return properties;
    }

    private static ModelRequest request(int timeoutMs) {
        return new ModelRequest(
                "deepseek-chat",
                "response-generator-v1",
                List.of(new ModelMessage("user", "rendered prompt")),
                0.0,
                timeoutMs,
                Map.of("operation", "RESPONSE_GENERATION")
        );
    }

    private static void assertCode(DeepSeekChatModelProvider provider, ModelRequest request, String code) {
        assertThatThrownBy(() -> provider.call(request))
                .isInstanceOf(ModelCallException.class)
                .extracting(ex -> ((ModelCallException) ex).code())
                .isEqualTo(code);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
