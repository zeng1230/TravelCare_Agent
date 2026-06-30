package travelcare_agent.agent.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SpringAiChatModelProvider implements ChatModelProvider {

    private final ChatModel chatModel;
    private final AgentProviderProperties properties;
    private final ObjectMapper objectMapper;

    public SpringAiChatModelProvider(ChatModel chatModel, ObjectMapper objectMapper) {
        this(chatModel, null, objectMapper);
    }

    public SpringAiChatModelProvider(AgentProviderProperties properties, ObjectMapper objectMapper) {
        this(null, properties, objectMapper);
    }

    public SpringAiChatModelProvider(ChatModel chatModel, AgentProviderProperties properties, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public ModelResponse call(ModelRequest request) {
        ChatModel modelClient = chatModel == null ? buildConfiguredChatModel(request) : chatModel;
        Prompt prompt = new Prompt(toSpringMessages(request.messages()), options(request));
        Instant startedAt = Instant.now();
        try {
            ChatResponse response = modelClient.call(prompt);
            if (response == null || response.getResult() == null) {
                throw new ModelCallException("MODEL_EMPTY_RESPONSE", "Spring AI returned an empty response");
            }
            Generation generation = response.getResult();
            String content = generation.getOutput() == null ? null : generation.getOutput().getText();
            if (content == null || content.isBlank()) {
                throw new ModelCallException("MODEL_EMPTY_RESPONSE", "Spring AI returned empty content");
            }
            String model = response.getMetadata() == null || response.getMetadata().getModel() == null
                    ? request.model()
                    : response.getMetadata().getModel();
            String finishReason = generation.getMetadata() == null ? null : generation.getMetadata().getFinishReason();
            ModelUsage usage = usage(response);
            return new ModelResponse(
                    content,
                    model,
                    providerName(),
                    usage,
                    Duration.between(startedAt, Instant.now()).toMillis(),
                    finishReason,
                    redactedSummary(response, content, model, finishReason, usage)
            );
        } catch (ModelCallException ex) {
            throw ex;
        } catch (ResourceAccessException ex) {
            if (hasTimeoutCause(ex)) {
                throw new ModelCallException("MODEL_TIMEOUT", "Spring AI provider request timed out", ex);
            }
            throw new ModelCallException("MODEL_HTTP_ERROR", "Spring AI provider request failed", ex);
        } catch (RestClientException ex) {
            if (hasTimeoutCause(ex)) {
                throw new ModelCallException("MODEL_TIMEOUT", "Spring AI provider request timed out", ex);
            }
            throw new ModelCallException("MODEL_HTTP_ERROR", "Spring AI provider request failed", ex);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            if (hasTimeoutCause(ex)) {
                throw new ModelCallException("MODEL_TIMEOUT", "Spring AI provider request timed out", ex);
            }
            throw new ModelCallException("MODEL_PROVIDER_FAILED", "Spring AI provider failed", ex);
        } catch (RuntimeException ex) {
            if (hasTimeoutCause(ex)) {
                throw new ModelCallException("MODEL_TIMEOUT", "Spring AI provider request timed out", ex);
            }
            throw new ModelCallException("MODEL_PROVIDER_FAILED", "Spring AI provider failed", ex);
        }
    }

    @Override
    public String providerName() {
        return "spring-ai";
    }

    private static List<Message> toSpringMessages(List<ModelMessage> messages) {
        List<Message> mapped = new ArrayList<>();
        for (ModelMessage message : messages) {
            String role = message.role() == null ? "" : message.role().trim().toLowerCase(Locale.ROOT);
            String content = message.content() == null ? "" : message.content();
            switch (role) {
                case "user" -> mapped.add(new UserMessage(content));
                case "system" -> mapped.add(new SystemMessage(content));
                case "assistant" -> mapped.add(new AssistantMessage(content));
                default -> throw new ModelCallException(
                        "MODEL_INVALID_MESSAGE_ROLE", "Unsupported model message role: " + safeRole(role));
            }
        }
        return mapped;
    }

    private static ChatOptions options(ModelRequest request) {
        OpenAiChatOptions options = new OpenAiChatOptions();
        options.setModel(request.model());
        options.setTemperature(request.temperature());
        options.setFunctions(java.util.Set.of());
        options.setFunctionCallbacks(java.util.List.of());
        return options;
    }

    private static ModelUsage usage(ChatResponse response) {
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        if (usage == null) {
            return new ModelUsage(null, null, null);
        }
        return new ModelUsage(toInteger(usage.getPromptTokens()), toInteger(usage.getGenerationTokens()),
                toInteger(usage.getTotalTokens()));
    }

    private String redactedSummary(
            ChatResponse response,
            String content,
            String model,
            String finishReason,
            ModelUsage usage
    ) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("provider", providerName());
        summary.put("model", model);
        summary.put("finishReason", finishReason);
        Map<String, Object> usageSummary = new LinkedHashMap<>();
        usageSummary.put("inputTokens", usage.inputTokens());
        usageSummary.put("outputTokens", usage.outputTokens());
        usageSummary.put("totalTokens", usage.totalTokens());
        summary.put("usage", usageSummary);
        summary.put("resultCount", response.getResults() == null ? 0 : response.getResults().size());
        summary.put("hasToolCalls", hasToolCalls(response));
        summary.put("contentHash", sha256(content));
        summary.put("metadata", Map.of("source", "spring-ai-chat-response-summary"));
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (JsonProcessingException ex) {
            throw new ModelCallException(
                    "MODEL_INVALID_RESPONSE", "Spring AI response summary could not be serialized", ex);
        }
    }

    private ChatModel buildConfiguredChatModel(ModelRequest request) {
        if (properties == null) {
            throw new ModelCallException("MODEL_PROVIDER_CONFIG_INVALID", "Spring AI ChatModel is not configured");
        }
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new ModelCallException("MODEL_API_KEY_MISSING", "Spring AI API key is missing");
        }
        int timeoutMs = request.timeoutMs() > 0 ? request.timeoutMs() : properties.getTimeoutMs();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutMs);
        requestFactory.setReadTimeout(timeoutMs);
        RestClient.Builder restClientBuilder = RestClient.builder().requestFactory(requestFactory);
        String baseUrl = useOpenAiDefaultBaseUrl(properties.getBaseUrl())
                ? "https://api.openai.com"
                : stripTrailingSlash(properties.getBaseUrl());
        OpenAiApi api = new OpenAiApi(baseUrl, properties.getApiKey(), restClientBuilder, WebClient.builder());
        OpenAiChatOptions options = new OpenAiChatOptions();
        options.setModel(properties.getModel());
        options.setTemperature(properties.getTemperature());
        options.setFunctions(java.util.Set.of());
        options.setFunctionCallbacks(java.util.List.of());
        return new OpenAiChatModel(api, options);
    }

    private static boolean hasToolCalls(ChatResponse response) {
        if (response.getResults() == null) return false;
        return response.getResults().stream()
                .map(Generation::getOutput)
                .anyMatch(output -> output != null && output.hasToolCalls());
    }

    private static Integer toInteger(Long value) {
        return value == null ? null : Math.toIntExact(value);
    }

    private static boolean hasTimeoutCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException) return true;
            current = current.getCause();
        }
        return false;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static String safeRole(String role) {
        if (role == null || role.isBlank()) return "blank";
        return role.matches("[a-zA-Z0-9_-]{1,32}") ? role : "invalid";
    }

    private static boolean useOpenAiDefaultBaseUrl(String baseUrl) {
        return baseUrl == null || baseUrl.isBlank() || "https://api.deepseek.com".equals(stripTrailingSlash(baseUrl));
    }

    private static String stripTrailingSlash(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
