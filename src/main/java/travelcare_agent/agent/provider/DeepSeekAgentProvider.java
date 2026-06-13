package travelcare_agent.agent.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.common.result.ResultCode;

import java.util.List;
import java.util.Map;

@Component
public class DeepSeekAgentProvider implements AgentProvider {

    private final AgentProviderProperties.DeepSeek properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public DeepSeekAgentProvider(AgentProviderProperties properties) {
        this(properties.getDeepseek());
    }

    public DeepSeekAgentProvider(AgentProviderProperties.DeepSeek properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return "deepseek";
    }

    @Override
    public AgentProviderResponse invoke(AgentProviderRequest request) {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new BusinessException(ResultCode.DEEPSEEK_API_KEY_MISSING);
        }
        try {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(properties.timeoutMs());
            requestFactory.setReadTimeout(properties.timeoutMs());
            RestClient client = RestClient.builder()
                    .requestFactory(requestFactory)
                    .baseUrl(stripTrailingSlash(properties.baseUrl()))
                    .defaultHeader("Authorization", "Bearer " + properties.apiKey())
                    .build();
            JsonNode root = client.post()
                    .uri("/chat/completions")
                    .body(Map.of(
                            "model", properties.model(),
                            "messages", List.of(Map.of("role", "user", "content", request.prompt())),
                            "response_format", Map.of("type", "json_object"),
                            "stream", false
                    ))
                    .retrieve()
                    .body(JsonNode.class);
            if (root == null) {
                throw new IllegalStateException("DEEPSEEK_EMPTY_RESPONSE");
            }
            String rawText = root.path("choices").path(0).path("message").path("content").asText(null);
            if (rawText == null) {
                throw new IllegalStateException("DEEPSEEK_EMPTY_CONTENT");
            }
            return new AgentProviderResponse(
                    rawText,
                    properties.model(),
                    intOrNull(root.path("usage").path("prompt_tokens")),
                    intOrNull(root.path("usage").path("completion_tokens"))
            );
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new IllegalStateException("DEEPSEEK_REQUEST_FAILED");
        }
    }

    private static Integer intOrNull(JsonNode node) {
        return node.isNumber() ? node.intValue() : null;
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "https://api.deepseek.com";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
