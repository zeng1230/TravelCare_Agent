package travelcare_agent.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import travelcare_agent.agent.prompt.PromptTemplateService;
import travelcare_agent.agent.provider.AgentProviderProperties;
import travelcare_agent.agent.provider.ChatModelProvider;
import travelcare_agent.agent.provider.MockChatModelProvider;
import travelcare_agent.agent.provider.ModelCallException;
import travelcare_agent.agent.provider.ModelMessage;
import travelcare_agent.agent.provider.ModelRequest;
import travelcare_agent.agent.provider.ModelResponse;
import travelcare_agent.agent.provider.ModelUsage;
import travelcare_agent.agentrun.service.AgentRunService;
import travelcare_agent.trace.SpanType;
import travelcare_agent.trace.TraceContextHolder;
import travelcare_agent.trace.TraceEventType;
import travelcare_agent.trace.TraceService;
import travelcare_agent.trace.TraceSnapshotType;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AgentModelService {

    private final ChatModelProvider primaryProvider;
    private final MockChatModelProvider fallbackProvider;
    private final AgentProviderProperties properties;
    private final PromptTemplateService promptTemplateService;
    private final AgentRunService agentRunService;
    private final ObjectMapper objectMapper;
    private final TraceService traceService;

    @Autowired
    public AgentModelService(
            ChatModelProvider primaryProvider,
            MockChatModelProvider fallbackProvider,
            AgentProviderProperties properties,
            PromptTemplateService promptTemplateService,
            AgentRunService agentRunService,
            ObjectMapper objectMapper,
            TraceService traceService
    ) {
        this.primaryProvider = primaryProvider;
        this.fallbackProvider = fallbackProvider;
        this.properties = properties;
        this.promptTemplateService = promptTemplateService;
        this.agentRunService = agentRunService;
        this.objectMapper = objectMapper;
        this.traceService = traceService;
    }

    public AgentModelService(
            ChatModelProvider primaryProvider,
            MockChatModelProvider fallbackProvider,
            PromptTemplateService promptTemplateService,
            AgentRunService agentRunService,
            ObjectMapper objectMapper
    ) {
        this(primaryProvider, fallbackProvider, new AgentProviderProperties(), promptTemplateService,
                agentRunService, objectMapper, null);
    }

    public MockIntentClassifier.IntentResult classifyIntentAndExtractSlots(
            Long sessionId,
            Long workflowId,
            List<Long> inputEventIds,
            String message
    ) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("message", message);
        JsonNode output = invokeStructured(
                sessionId,
                workflowId,
                inputEventIds,
                "INTENT_CLASSIFICATION",
                PromptTemplateService.INTENT_CLASSIFIER_V1,
                input
        );
        return new MockIntentClassifier.IntentResult(
                requiredText(output, "intent"),
                nullableText(output, "orderNo")
        );
    }

    public String generateCustomerAnswer(
            Long sessionId,
            Long workflowId,
            List<Long> inputEventIds,
            String deterministicAnswer
    ) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("deterministicAnswer", deterministicAnswer);
        JsonNode output = invokeStructured(
                sessionId,
                workflowId,
                inputEventIds,
                "RESPONSE_GENERATION",
                PromptTemplateService.RESPONSE_GENERATOR_V1,
                input
        );
        return requiredText(output, "answer");
    }

    private JsonNode invokeStructured(
            Long sessionId,
            Long workflowId,
            List<Long> inputEventIds,
            String runType,
            String promptVersion,
            Map<String, Object> input
    ) {
        travelcare_agent.dryrun.SideEffectGuard.checkCurrent(
                travelcare_agent.dryrun.SideEffectOperation.EXTERNAL_PROVIDER_CALL
        );
        String requestJson = toJson(input);
        String prompt = promptTemplateService.render(promptVersion, requestJson);
        Map<String, Object> metadata = new LinkedHashMap<>(input);
        metadata.put("operation", runType);
        ModelRequest request = new ModelRequest(
                properties.getModel(),
                promptVersion,
                List.of(new ModelMessage("user", prompt)),
                properties.getTemperature(),
                properties.getTimeoutMs(),
                metadata
        );
        if (traceService != null) {
            traceService.recordCurrentSnapshot(TraceSnapshotType.MODEL_INPUT,
                    "MODEL_OPERATION", runType, Map.of(
                            "operation", runType,
                            "promptVersion", promptVersion,
                            "input", input
                    ));
        }
        try {
            return invokeAndRecord(sessionId, workflowId, inputEventIds, runType, promptVersion,
                    requestJson, primaryProvider, request);
        } catch (RuntimeException ex) {
            return fallback(sessionId, workflowId, inputEventIds, promptVersion, requestJson, request);
        }
    }

    private JsonNode fallback(
            Long sessionId,
            Long workflowId,
            List<Long> inputEventIds,
            String promptVersion,
            String requestJson,
            ModelRequest request
    ) {
        TraceContextHolder.TraceContext context = TraceContextHolder.current();
        if (traceService != null && context != null) {
            traceService.recordEvent(context.traceId(), context.spanId(), TraceEventType.FALLBACK,
                    "model-provider-fallback", Map.of("provider", fallbackProvider.providerName()));
        }
        try {
            return invokeAndRecord(sessionId, workflowId, inputEventIds, "FALLBACK", promptVersion,
                    requestJson, fallbackProvider, request);
        } catch (RuntimeException ex) {
            throw new ModelCallException("MODEL_FALLBACK_FAILED", "Deterministic model fallback failed", ex);
        }
    }

    private JsonNode invokeAndRecord(
            Long sessionId,
            Long workflowId,
            List<Long> inputEventIds,
            String persistedRunType,
            String promptVersion,
            String requestJson,
            ChatModelProvider provider,
            ModelRequest request
    ) {
        Instant startedAt = Instant.now();
        TraceService.SpanHandle span = traceService == null ? TraceService.SpanHandle.unavailable()
                : traceService.startSpan("FALLBACK".equals(persistedRunType) ? SpanType.FALLBACK : SpanType.MODEL,
                persistedRunType, Map.of("provider", provider.providerName(), "promptVersion", promptVersion));
        ModelResponse response = null;
        try {
            response = provider.call(request);
            if (response == null || response.content() == null || response.content().isBlank()) {
                throw new ModelCallException("MODEL_EMPTY_RESPONSE", "Model returned empty content");
            }
            JsonNode parsed = objectMapper.readTree(response.content());
            validateResponse(request.metadata().get("operation"), parsed);
            if (traceService != null) {
                Map<String, Object> outputSnapshot = new LinkedHashMap<>();
                outputSnapshot.put("operation", request.metadata().get("operation"));
                outputSnapshot.put("provider", provider.providerName());
                outputSnapshot.put("model", response.model());
                outputSnapshot.put("promptVersion", promptVersion);
                outputSnapshot.put("output", parsed);
                traceService.recordCurrentSnapshot(TraceSnapshotType.MODEL_OUTPUT,
                        "MODEL_OPERATION", request.metadata().get("operation").toString(), outputSnapshot);
            }
            record(sessionId, workflowId, inputEventIds, persistedRunType, provider.providerName(),
                    response.model(), promptVersion, requestJson, responseJson(response.content()), response.usage(),
                    startedAt, "SUCCEEDED", null);
            if (traceService != null) {
                traceService.finishSpanSuccess(span, null, Map.of(
                        "provider", provider.providerName(),
                        "model", response.model(),
                        "promptVersion", promptVersion
                ));
            }
            return parsed;
        } catch (JsonProcessingException ex) {
            ModelCallException mapped = new ModelCallException(
                    "MODEL_INVALID_RESPONSE", "Model response was not valid JSON", ex
            );
            recordFailure(sessionId, workflowId, inputEventIds, persistedRunType, promptVersion,
                    requestJson, provider, response, startedAt, span, mapped);
            throw mapped;
        } catch (ModelCallException ex) {
            recordFailure(sessionId, workflowId, inputEventIds, persistedRunType, promptVersion,
                    requestJson, provider, response, startedAt, span, ex);
            throw ex;
        } catch (RuntimeException ex) {
            ModelCallException mapped = new ModelCallException(
                    "MODEL_PROVIDER_FAILED", "Model provider failed", ex
            );
            recordFailure(sessionId, workflowId, inputEventIds, persistedRunType, promptVersion,
                    requestJson, provider, response, startedAt, span, mapped);
            throw mapped;
        }
    }

    private void recordFailure(
            Long sessionId,
            Long workflowId,
            List<Long> inputEventIds,
            String runType,
            String promptVersion,
            String requestJson,
            ChatModelProvider provider,
            ModelResponse response,
            Instant startedAt,
            TraceService.SpanHandle span,
            ModelCallException error
    ) {
        record(sessionId, workflowId, inputEventIds, runType, provider.providerName(), model(response),
                promptVersion, requestJson, responseJson(content(response)), usage(response), startedAt,
                "FAILED_GENERATION", error.code());
        if (traceService != null) {
            traceService.finishSpanFailure(span, error.code(), error,
                    Map.of("provider", provider.providerName()));
        }
    }

    private void record(
            Long sessionId,
            Long workflowId,
            List<Long> inputEventIds,
            String runType,
            String provider,
            String model,
            String promptVersion,
            String requestJson,
            String responseJson,
            ModelUsage usage,
            Instant startedAt,
            String status,
            String errorCode
    ) {
        agentRunService.recordModelCall(
                sessionId, workflowId, runType, provider, model, promptVersion, inputEventIds,
                requestJson, responseJson, inputTokens(usage), outputTokens(usage),
                Duration.between(startedAt, Instant.now()).toMillis(), status, errorCode
        );
    }

    private void validateResponse(Object operation, JsonNode parsed) {
        if (parsed == null || !parsed.isObject()) {
            throw new ModelCallException("MODEL_INVALID_RESPONSE", "Model response must be a JSON object");
        }
        String requiredField = "INTENT_CLASSIFICATION".equals(operation) ? "intent" : "answer";
        String requiredValue = nullableText(parsed, requiredField);
        if (requiredValue == null || requiredValue.isBlank()) {
            throw new ModelCallException("MODEL_INVALID_RESPONSE", "Model response is missing a required field");
        }
    }

    private String requiredText(JsonNode node, String field) {
        String value = nullableText(node, field);
        if (value == null || value.isBlank()) {
            throw new ModelCallException("MODEL_INVALID_RESPONSE", "Model response is missing a required field");
        }
        return value;
    }

    private String nullableText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new ModelCallException("MODEL_REQUEST_SERIALIZATION_FAILED",
                    "Model request serialization failed", ex);
        }
    }

    private String responseJson(String content) {
        return toJson(Map.of("content", content == null ? "" : content));
    }

    private static String content(ModelResponse response) {
        return response == null ? null : response.content();
    }

    private static String model(ModelResponse response) {
        return response == null ? null : response.model();
    }

    private static ModelUsage usage(ModelResponse response) {
        return response == null ? null : response.usage();
    }

    private static Integer inputTokens(ModelUsage usage) {
        return usage == null ? null : usage.inputTokens();
    }

    private static Integer outputTokens(ModelUsage usage) {
        return usage == null ? null : usage.outputTokens();
    }
}
