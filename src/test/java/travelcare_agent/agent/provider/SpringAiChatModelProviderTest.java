package travelcare_agent.agent.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringAiChatModelProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsSuccessfulResponseAndRedactedCanonicalSummary() throws Exception {
        CapturingChatModel chatModel = new CapturingChatModel(response(
                "{\"intent\":\"FAQ\",\"confidence\":0.99,\"slots\":{},\"answerDraft\":\"private-draft\",\"citations\":[],\"riskFlags\":[]}",
                "gpt-4o-mini",
                "stop",
                new DefaultUsage(11L, 7L, 18L),
                false
        ));
        SpringAiChatModelProvider provider = new SpringAiChatModelProvider(chatModel, objectMapper);

        ModelResponse response = provider.call(request(List.of(new ModelMessage("user", "rendered prompt"))));

        assertThat(response.content()).contains("\"answerDraft\":\"private-draft\"");
        assertThat(response.provider()).isEqualTo("spring-ai");
        assertThat(response.model()).isEqualTo("gpt-4o-mini");
        assertThat(response.finishReason()).isEqualTo("stop");
        assertThat(response.usage()).isEqualTo(new ModelUsage(11, 7, 18));
        assertThat(response.latencyMs()).isNotNegative();
        assertThat(chatModel.prompt.get().getInstructions()).singleElement().isInstanceOf(UserMessage.class);

        JsonNode summary = objectMapper.readTree(response.rawResponse());
        assertThat(summary.path("provider").asText()).isEqualTo("spring-ai");
        assertThat(summary.path("model").asText()).isEqualTo("gpt-4o-mini");
        assertThat(summary.path("finishReason").asText()).isEqualTo("stop");
        assertThat(summary.path("resultCount").asInt()).isEqualTo(1);
        assertThat(summary.path("hasToolCalls").asBoolean()).isFalse();
        assertThat(summary.path("contentHash").asText()).hasSize(64);
        assertThat(summary.path("usage").path("inputTokens").asInt()).isEqualTo(11);
        assertThat(response.rawResponse()).doesNotContain(
                "rendered prompt", "apiKey", "Authorization", "Bearer", "answerDraft", "private-draft");
    }

    @Test
    void configuredPathRejectsBlankApiKey() {
        AgentProviderProperties properties = properties("", "https://api.deepseek.com");
        SpringAiChatModelProvider provider = new SpringAiChatModelProvider(properties, objectMapper);

        assertCode(provider, "MODEL_API_KEY_MISSING");
    }

    @Test
    void externalChatModelPathDoesNotValidateApiKey() {
        AgentProviderProperties properties = properties("", "https://api.deepseek.com");
        CapturingChatModel chatModel = new CapturingChatModel(response("ok", "gpt-test", "stop", null, false));
        SpringAiChatModelProvider provider = new SpringAiChatModelProvider(chatModel, properties, objectMapper);

        ModelResponse response = provider.call(request(List.of(new ModelMessage("user", "rendered prompt"))));

        assertThat(response.content()).isEqualTo("ok");
        assertThat(chatModel.prompt.get()).isNotNull();
    }

    @Test
    void configuredPathUsesExplicitDeepSeekCompatibleBaseUrl() {
        AgentProviderProperties properties = properties("test-key", "https://api.deepseek.com");
        CapturingFactory factory = new CapturingFactory(response("ok", "deepseek-chat", "stop", null, false));
        SpringAiChatModelProvider provider = new SpringAiChatModelProvider(null, properties, objectMapper, factory);

        provider.call(request(List.of(new ModelMessage("user", "rendered prompt"))));

        assertThat(factory.baseUrl.get()).isEqualTo("https://api.deepseek.com");
    }

    @Test
    void configuredPathBuildsChatModelOnlyOnce() {
        AgentProviderProperties properties = properties("test-key", "https://api.deepseek.com");
        CapturingFactory factory = new CapturingFactory(response("ok", "deepseek-chat", "stop", null, false));
        SpringAiChatModelProvider provider = new SpringAiChatModelProvider(null, properties, objectMapper, factory);

        provider.call(request(List.of(new ModelMessage("user", "first prompt"))));
        provider.call(request(List.of(new ModelMessage("user", "second prompt"))));

        assertThat(factory.builds).hasValue(1);
    }

    @Test
    void requestOptionsDisableSpringAiFunctionCalling() {
        CapturingChatModel chatModel = new CapturingChatModel(response("ok", "gpt-test", "stop", null, false));
        SpringAiChatModelProvider provider = new SpringAiChatModelProvider(chatModel, objectMapper);

        provider.call(request(List.of(new ModelMessage("user", "rendered prompt"))));

        org.springframework.ai.openai.OpenAiChatOptions options =
                (org.springframework.ai.openai.OpenAiChatOptions) chatModel.prompt.get().getOptions();
        assertThat(options.getFunctions()).isEmpty();
        assertThat(options.getFunctionCallbacks()).isEmpty();
    }

    @Test
    void mapsSystemUserAndAssistantMessages() {
        CapturingChatModel chatModel = new CapturingChatModel(response("ok", "gpt-test", "stop", null, false));
        SpringAiChatModelProvider provider = new SpringAiChatModelProvider(chatModel, objectMapper);

        provider.call(request(List.of(
                new ModelMessage("system", "system prompt"),
                new ModelMessage("user", "user prompt"),
                new ModelMessage("assistant", "assistant context")
        )));

        List<Message> instructions = chatModel.prompt.get().getInstructions();
        assertThat(instructions.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(instructions.get(1)).isInstanceOf(UserMessage.class);
        assertThat(instructions.get(2)).isInstanceOf(AssistantMessage.class);
    }

    @Test
    void rejectsUnknownRoleWithoutLoggingContentInError() {
        SpringAiChatModelProvider provider = new SpringAiChatModelProvider(
                new CapturingChatModel(response("ok", "gpt-test", "stop", null, false)), objectMapper);

        assertThatThrownBy(() -> provider.call(request(List.of(new ModelMessage("tool", "secret prompt")))))
                .isInstanceOfSatisfying(ModelCallException.class, error -> {
                    assertThat(error.code()).isEqualTo("MODEL_INVALID_MESSAGE_ROLE");
                    assertThat(error.getMessage()).contains("tool");
                    assertThat(error.getMessage()).doesNotContain("secret prompt");
                });
    }

    @Test
    void mapsEmptyContentToEmptyResponseError() {
        SpringAiChatModelProvider provider = new SpringAiChatModelProvider(
                new CapturingChatModel(response("", "gpt-test", "stop", null, false)), objectMapper);

        assertCode(provider, "MODEL_EMPTY_RESPONSE");
    }

    @Test
    void mapsProviderExceptionToProviderFailure() {
        SpringAiChatModelProvider provider = new SpringAiChatModelProvider(prompt -> {
            throw new IllegalStateException("provider unavailable");
        }, objectMapper);

        assertCode(provider, "MODEL_PROVIDER_FAILED");
    }

    @Test
    void mapsTimeoutExceptionToTimeout() {
        SpringAiChatModelProvider provider = new SpringAiChatModelProvider(prompt -> {
            throw new ResourceAccessException("timed out", new SocketTimeoutException("read timed out"));
        }, objectMapper);

        assertCode(provider, "MODEL_TIMEOUT");
    }

    @Test
    void mapsMissingChatModelConfigurationToConfigError() {
        SpringAiChatModelProvider provider = new SpringAiChatModelProvider((ChatModel) null, objectMapper);

        assertCode(provider, "MODEL_PROVIDER_CONFIG_INVALID");
    }

    @Test
    void largeUsageValuesAreSaturatedInsteadOfFailing() {
        SpringAiChatModelProvider provider = new SpringAiChatModelProvider(new CapturingChatModel(response(
                "ok", "gpt-test", "stop",
                new DefaultUsage(((long) Integer.MAX_VALUE) + 10L, ((long) Integer.MAX_VALUE) + 20L,
                        ((long) Integer.MAX_VALUE) + 30L),
                false
        )), objectMapper);

        ModelResponse response = provider.call(request(List.of(new ModelMessage("user", "rendered prompt"))));

        assertThat(response.usage()).isEqualTo(new ModelUsage(
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    private static void assertCode(SpringAiChatModelProvider provider, String expectedCode) {
        assertThatThrownBy(() -> provider.call(request(List.of(new ModelMessage("user", "rendered prompt")))))
                .isInstanceOf(ModelCallException.class)
                .extracting(ex -> ((ModelCallException) ex).code())
                .isEqualTo(expectedCode);
    }

    private static ModelRequest request(List<ModelMessage> messages) {
        return new ModelRequest("gpt-4o-mini", "response-generator-v1", messages, 0.2, 1000,
                Map.of("operation", "RESPONSE_GENERATION", "traceId", "trace-1"));
    }

    private static AgentProviderProperties properties(String apiKey, String baseUrl) {
        AgentProviderProperties properties = new AgentProviderProperties();
        properties.setProvider(AgentProviderType.SPRING_AI);
        properties.setModel("deepseek-chat");
        properties.setApiKey(apiKey);
        properties.setBaseUrl(baseUrl);
        properties.setTimeoutMs(1000);
        properties.setTemperature(0.0);
        return properties;
    }

    private static ChatResponse response(String content, String model, String finishReason,
            DefaultUsage usage, boolean hasToolCalls) {
        AssistantMessage assistant = hasToolCalls
                ? new AssistantMessage(content, Map.of(), List.of(new AssistantMessage.ToolCall("1", "function", "GetOrderTool", "{}")))
                : new AssistantMessage(content);
        Generation generation = new Generation(assistant,
                ChatGenerationMetadata.builder().finishReason(finishReason).build());
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .model(model)
                .usage(usage)
                .build();
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
