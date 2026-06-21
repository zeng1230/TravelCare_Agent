package travelcare_agent.agent.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DeepSeekChatModelProvider implements ChatModelProvider {

    private final AgentProviderProperties properties;
    private final ObjectMapper objectMapper;

    public DeepSeekChatModelProvider(AgentProviderProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public ModelResponse call(ModelRequest request) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new ModelCallException("MODEL_API_KEY_MISSING", "DeepSeek API key is missing");
        }
        int timeoutMs = request.timeoutMs() > 0 ? request.timeoutMs() : properties.getTimeoutMs();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutMs);
        requestFactory.setReadTimeout(timeoutMs);
        RestClient client = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(stripTrailingSlash(properties.getBaseUrl()))
                .defaultHeader("Authorization", "Bearer " + properties.getApiKey())
                .build();
        Instant startedAt = Instant.now();
        try {
            String raw = client.post()
                    .uri("/chat/completions")
                    .body(requestBody(request))
                    .retrieve()
                    .body(String.class);
            if (raw == null || raw.isBlank()) {
                throw new ModelCallException("MODEL_EMPTY_RESPONSE", "Model returned an empty response");
            }
            JsonNode root = parse(raw);
            String content = root.path("choices").path(0).path("message").path("content").asText(null);
            if (content == null || content.isBlank()) {
                throw new ModelCallException("MODEL_EMPTY_RESPONSE", "Model returned empty content");
            }
            Integer inputTokens = intOrNull(root.path("usage").path("prompt_tokens"));
            Integer outputTokens = intOrNull(root.path("usage").path("completion_tokens"));
            Integer totalTokens = intOrNull(root.path("usage").path("total_tokens"));
            if (totalTokens == null && inputTokens != null && outputTokens != null) {
                totalTokens = inputTokens + outputTokens;
            }
            String responseModel = root.path("model").asText(request.model());
            String finishReason = root.path("choices").path(0).path("finish_reason").asText(null);
            return new ModelResponse(
                    content,
                    responseModel,
                    providerName(),
                    new ModelUsage(inputTokens, outputTokens, totalTokens),
                    Duration.between(startedAt, Instant.now()).toMillis(),
                    finishReason,
                    raw
            );
        } catch (ModelCallException ex) {
            throw ex;
        } catch (RestClientResponseException ex) {
            throw new ModelCallException("MODEL_HTTP_ERROR", "DeepSeek HTTP request failed", ex);
        } catch (ResourceAccessException ex) {
            if (hasTimeoutCause(ex)) {
                throw new ModelCallException("MODEL_TIMEOUT", "DeepSeek request timed out", ex);
            }
            throw new ModelCallException("MODEL_HTTP_ERROR", "DeepSeek request failed", ex);
        } catch (RestClientException ex) {
            if (hasTimeoutCause(ex)) {
                throw new ModelCallException("MODEL_TIMEOUT", "DeepSeek request timed out", ex);
            }
            throw new ModelCallException("MODEL_HTTP_ERROR", "DeepSeek request failed", ex);
        } catch (RuntimeException ex) {
            throw new ModelCallException("MODEL_INVALID_RESPONSE", "DeepSeek response was invalid", ex);
        }
    }

    @Override
    public String providerName() {
        return "deepseek";
    }

    private JsonNode parse(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (JsonProcessingException ex) {
            throw new ModelCallException("MODEL_INVALID_RESPONSE", "DeepSeek response was not valid JSON", ex);
        }
    }

    private Map<String, Object> requestBody(ModelRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        List<Map<String, String>> messages = new ArrayList<>();
        for (ModelMessage message : request.messages()) {
            messages.add(Map.of("role", message.role(), "content", message.content()));
        }
        body.put("messages", messages);
        if (request.temperature() != null) body.put("temperature", request.temperature());
        body.put("response_format", Map.of("type", "json_object"));
        body.put("stream", false);
        return body;
    }

    private static boolean hasTimeoutCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException) return true;
            current = current.getCause();
        }
        return false;
    }

    private static Integer intOrNull(JsonNode node) {
        return node.isNumber() ? node.intValue() : null;
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) return "https://api.deepseek.com";
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
