package travelcare_agent.agent.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.chat.model.ChatModel;

import java.util.Map;

public class DeepSeekSpringAiChatModelProvider implements ChatModelProvider {

    private static final String PROVIDER = "deepseek";
    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    private static final String SUMMARY_SOURCE = "deepseek-spring-ai-summary";
    private static final String SUMMARY_FALLBACK_SOURCE = "deepseek-spring-ai-summary-patch-fallback";

    private final ChatModelProvider delegate;
    private final ObjectMapper objectMapper;

    public DeepSeekSpringAiChatModelProvider(AgentProviderProperties properties, ObjectMapper objectMapper) {
        this(null, properties, objectMapper);
    }

    public DeepSeekSpringAiChatModelProvider(ChatModel chatModel, ObjectMapper objectMapper) {
        this(chatModel, null, objectMapper);
    }

    public DeepSeekSpringAiChatModelProvider(
            ChatModel chatModel,
            AgentProviderProperties properties,
            ObjectMapper objectMapper
    ) {
        this(chatModel, properties, objectMapper, SpringAiChatModelProvider::createConfiguredChatModel);
    }

    DeepSeekSpringAiChatModelProvider(
            ChatModel chatModel,
            AgentProviderProperties properties,
            ObjectMapper objectMapper,
            SpringAiChatModelProvider.ConfiguredChatModelFactory configuredChatModelFactory
    ) {
        if (chatModel == null && properties == null) {
            throw new IllegalArgumentException("Either chatModel or properties must be provided");
        }
        this.objectMapper = objectMapper;
        AgentProviderProperties deepSeekProperties = chatModel == null ? deepSeekProperties(properties) : properties;
        this.delegate = new SpringAiChatModelProvider(
                chatModel,
                deepSeekProperties,
                objectMapper,
                configuredChatModelFactory
        );
    }

    DeepSeekSpringAiChatModelProvider(ChatModelProvider delegate, ObjectMapper objectMapper) {
        this.delegate = delegate;
        this.objectMapper = objectMapper;
    }

    @Override
    public ModelResponse call(ModelRequest request) {
        ModelResponse response = delegate.call(request);
        return new ModelResponse(
                response.content(),
                response.model(),
                PROVIDER,
                response.usage(),
                response.latencyMs(),
                response.finishReason(),
                deepSeekRawResponse(response.rawResponse())
        );
    }

    @Override
    public String providerName() {
        return PROVIDER;
    }

    private String deepSeekRawResponse(String rawResponse) {
        try {
            JsonNode root = objectMapper.readTree(rawResponse);
            if (!(root instanceof ObjectNode objectNode)) {
                return fallbackSummary();
            }
            objectNode.put("provider", PROVIDER);
            ObjectNode metadata = objectNode.withObject("/metadata");
            metadata.put("source", SUMMARY_SOURCE);
            return objectMapper.writeValueAsString(objectNode);
        } catch (RuntimeException | JsonProcessingException ex) {
            return fallbackSummary();
        }
    }

    private String fallbackSummary() {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "provider", PROVIDER,
                    "metadata", Map.of("source", SUMMARY_FALLBACK_SOURCE),
                    "patchFailed", true
            ));
        } catch (JsonProcessingException ex) {
            return "{\"provider\":\"deepseek\",\"metadata\":{\"source\":\"deepseek-spring-ai-summary-patch-fallback\"},\"patchFailed\":true}";
        }
    }

    private static AgentProviderProperties deepSeekProperties(AgentProviderProperties source) {
        AgentProviderProperties properties = new AgentProviderProperties();
        properties.setProvider(AgentProviderType.DEEPSEEK);
        properties.setModel(source.getModel());
        properties.setPromptVersion(source.getPromptVersion());
        properties.setTimeoutMs(source.getTimeoutMs());
        properties.setApiKey(source.getApiKey());
        properties.setBaseUrl(source.getBaseUrl() == null || source.getBaseUrl().isBlank()
                ? DEFAULT_BASE_URL
                : source.getBaseUrl());
        properties.setTemperature(source.getTemperature());
        properties.setDeepseek(source.getDeepseek());
        return properties;
    }
}
