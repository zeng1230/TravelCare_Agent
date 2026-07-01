package travelcare_agent.agent.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeepSeekSpringAiChatModelProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rejectsMissingChatModelAndPropertiesAtConstruction() {
        assertThatThrownBy(() -> new DeepSeekSpringAiChatModelProvider(null, null, objectMapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chatModel or properties");
    }

    @Test
    void mapsDelegateResponseToDeepSeekProviderAndSummary() throws Exception {
        CapturingChatModel chatModel = new CapturingChatModel(response("ok", "deepseek-chat", "stop"));
        DeepSeekSpringAiChatModelProvider provider = new DeepSeekSpringAiChatModelProvider(chatModel, objectMapper);

        ModelResponse response = provider.call(request());

        assertThat(response.provider()).isEqualTo("deepseek");
        assertThat(response.model()).isEqualTo("deepseek-chat");
        JsonNode raw = objectMapper.readTree(response.rawResponse());
        assertThat(raw.path("provider").asText()).isEqualTo("deepseek");
        assertThat(raw.path("metadata").path("source").asText()).isEqualTo("deepseek-spring-ai-summary");
        assertThat(response.rawResponse()).doesNotContain("rendered prompt", "Authorization", "Bearer", "apiKey");
    }

    @Test
    void rawResponsePatchFallbackStillUsesDeepSeekProvider() throws Exception {
        ChatModelProvider delegate = new ChatModelProvider() {
            @Override
            public ModelResponse call(ModelRequest request) {
                return new ModelResponse("ok", "deepseek-chat", "spring-ai", null, 1, "stop", "not-json");
            }

            @Override
            public String providerName() {
                return "spring-ai";
            }
        };
        DeepSeekSpringAiChatModelProvider provider =
                new DeepSeekSpringAiChatModelProvider(delegate, objectMapper);

        ModelResponse response = provider.call(request());

        JsonNode raw = objectMapper.readTree(response.rawResponse());
        assertThat(response.provider()).isEqualTo("deepseek");
        assertThat(raw.path("provider").asText()).isEqualTo("deepseek");
        assertThat(raw.path("metadata").path("source").asText())
                .isEqualTo("deepseek-spring-ai-summary-patch-fallback");
        assertThat(raw.path("patchFailed").asBoolean()).isTrue();
        assertThat(response.rawResponse()).doesNotContain("\"provider\":\"spring-ai\"", "not-json");
    }

    @Test
    void configuredPathUsesDeepSeekDefaultBaseUrlWhenBlank() {
        AgentProviderProperties properties = properties("test-key", "");
        CapturingFactory factory = new CapturingFactory(response("ok", "deepseek-chat", "stop"));
        DeepSeekSpringAiChatModelProvider provider =
                new DeepSeekSpringAiChatModelProvider(null, properties, objectMapper, factory);

        provider.call(request());

        assertThat(factory.baseUrl.get()).isEqualTo("https://api.deepseek.com");
        assertThat(factory.builds).hasValue(1);
    }

    @Test
    void configuredPathUsesExplicitBaseUrl() {
        AgentProviderProperties properties = properties("test-key", "https://proxy.example.test/v1");
        CapturingFactory factory = new CapturingFactory(response("ok", "deepseek-chat", "stop"));
        DeepSeekSpringAiChatModelProvider provider =
                new DeepSeekSpringAiChatModelProvider(null, properties, objectMapper, factory);

        provider.call(request());

        assertThat(factory.baseUrl.get()).isEqualTo("https://proxy.example.test/v1");
    }

    @Test
    void disablesSpringAiFunctionCalling() {
        CapturingChatModel chatModel = new CapturingChatModel(response("ok", "deepseek-chat", "stop"));
        DeepSeekSpringAiChatModelProvider provider = new DeepSeekSpringAiChatModelProvider(chatModel, objectMapper);

        provider.call(request());

        org.springframework.ai.openai.OpenAiChatOptions options =
                (org.springframework.ai.openai.OpenAiChatOptions) chatModel.prompt.get().getOptions();
        assertThat(options.getFunctions()).isEmpty();
        assertThat(options.getFunctionCallbacks()).isEmpty();
    }

    private static ModelRequest request() {
        return new ModelRequest("deepseek-chat", "response-generator-v1",
                List.of(new ModelMessage("user", "rendered prompt")), 0.0, 1000, Map.of());
    }

    private static AgentProviderProperties properties(String apiKey, String baseUrl) {
        AgentProviderProperties properties = new AgentProviderProperties();
        properties.setProvider(AgentProviderType.DEEPSEEK);
        properties.setModel("deepseek-chat");
        properties.setApiKey(apiKey);
        properties.setBaseUrl(baseUrl);
        properties.setTimeoutMs(1000);
        properties.setTemperature(0.0);
        return properties;
    }

    private static ChatResponse response(String content, String model, String finishReason) {
        AssistantMessage assistant = new AssistantMessage(content);
        Generation generation = new Generation(assistant,
                ChatGenerationMetadata.builder().finishReason(finishReason).build());
        ChatResponseMetadata metadata = ChatResponseMetadata.builder().model(model).build();
        return new ChatResponse(List.of(generation), metadata);
    }

    private static class CapturingChatModel implements ChatModel {
        private final ChatResponse response;
        private final AtomicReference<Prompt> prompt = new AtomicReference<>();

        private CapturingChatModel(ChatResponse response) {
            this.response = response;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            this.prompt.set(prompt);
            return response;
        }
    }

    private static class CapturingFactory implements SpringAiChatModelProvider.ConfiguredChatModelFactory {
        private final ChatResponse response;
        private final AtomicInteger builds = new AtomicInteger();
        private final AtomicReference<String> baseUrl = new AtomicReference<>();

        private CapturingFactory(ChatResponse response) {
            this.response = response;
        }

        @Override
        public ChatModel create(AgentProviderProperties properties, String baseUrl) {
            builds.incrementAndGet();
            this.baseUrl.set(baseUrl);
            return new CapturingChatModel(response);
        }
    }
}
