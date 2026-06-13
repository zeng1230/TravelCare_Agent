package travelcare_agent.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import travelcare_agent.agent.prompt.PromptTemplateService;
import travelcare_agent.agent.provider.AgentProvider;
import travelcare_agent.agent.provider.AgentProviderProperties;
import travelcare_agent.agent.provider.AgentProviderRequest;
import travelcare_agent.agent.provider.AgentProviderResponse;
import travelcare_agent.agent.provider.DeepSeekAgentProvider;
import travelcare_agent.agent.provider.MockAgentProvider;
import travelcare_agent.agentrun.service.AgentRunService;
import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.common.result.ResultCode;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import travelcare_agent.trace.*;

@Service
public class AgentModelService {

    private final AgentProvider primaryProvider;
    private final MockAgentProvider fallbackProvider;
    private final PromptTemplateService promptTemplateService;
    private final AgentRunService agentRunService;
    private final ObjectMapper objectMapper;
    private final TraceService traceService;

    @Autowired
    public AgentModelService(
            AgentProviderProperties properties,
            MockAgentProvider mockAgentProvider,
            DeepSeekAgentProvider deepSeekAgentProvider,
            PromptTemplateService promptTemplateService,
            AgentRunService agentRunService,
            ObjectMapper objectMapper,
            TraceService traceService
    ) {
        this(
                "deepseek".equalsIgnoreCase(properties.getProvider()) ? deepSeekAgentProvider : mockAgentProvider,
                mockAgentProvider,
                promptTemplateService,
                agentRunService,
                objectMapper,
                traceService
        );
    }

    public AgentModelService(AgentProviderProperties properties, MockAgentProvider mockAgentProvider,
            DeepSeekAgentProvider deepSeekAgentProvider, PromptTemplateService promptTemplateService,
            AgentRunService agentRunService, ObjectMapper objectMapper) {
        this("deepseek".equalsIgnoreCase(properties.getProvider()) ? deepSeekAgentProvider : mockAgentProvider,
                mockAgentProvider, promptTemplateService, agentRunService, objectMapper, null);
    }

    public AgentModelService(
            AgentProvider primaryProvider,
            MockAgentProvider fallbackProvider,
            PromptTemplateService promptTemplateService,
            AgentRunService agentRunService,
            ObjectMapper objectMapper
    ) {
        this(primaryProvider, fallbackProvider, promptTemplateService, agentRunService, objectMapper, null);
    }

    public AgentModelService(AgentProvider primaryProvider, MockAgentProvider fallbackProvider,
            PromptTemplateService promptTemplateService, AgentRunService agentRunService,
            ObjectMapper objectMapper, TraceService traceService) {
        this.primaryProvider = primaryProvider;
        this.fallbackProvider = fallbackProvider;
        this.promptTemplateService = promptTemplateService;
        this.agentRunService = agentRunService;
        this.objectMapper = objectMapper;
        this.traceService = traceService;
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
        travelcare_agent.dryrun.SideEffectGuard.checkCurrent(travelcare_agent.dryrun.SideEffectOperation.EXTERNAL_PROVIDER_CALL);
        String requestJson = toJson(input);
        String prompt = promptTemplateService.render(promptVersion, requestJson);
        AgentProviderRequest request = new AgentProviderRequest(runType, promptVersion, prompt, input);
        if (traceService != null) traceService.recordCurrentSnapshot(TraceSnapshotType.MODEL_INPUT,
                "MODEL_OPERATION", runType, Map.of(
                        "operation", runType,
                        "promptVersion", promptVersion,
                        "input", input
                ));
        try {
            return invokeAndRecord(sessionId, workflowId, inputEventIds, runType, promptVersion, requestJson, primaryProvider, request);
        } catch (BusinessException ex) {
            if (ex.getResultCode() == ResultCode.DEEPSEEK_API_KEY_MISSING) {
                throw ex;
            }
            return fallback(sessionId, workflowId, inputEventIds, promptVersion, requestJson, request);
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
            AgentProviderRequest request
    ) {
        TraceContextHolder.TraceContext context = TraceContextHolder.current();
        if (traceService != null && context != null) traceService.recordEvent(context.traceId(), context.spanId(),
                TraceEventType.FALLBACK, "model-provider-fallback", Map.of("provider", fallbackProvider.name()));
        try {
            return invokeAndRecord(sessionId, workflowId, inputEventIds, "FALLBACK", promptVersion, requestJson, fallbackProvider, request);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("AGENT_PROVIDER_FALLBACK_FAILED");
        }
    }

    private JsonNode invokeAndRecord(
            Long sessionId,
            Long workflowId,
            List<Long> inputEventIds,
            String persistedRunType,
            String promptVersion,
            String requestJson,
            AgentProvider provider,
            AgentProviderRequest request
    ) {
        Instant startedAt = Instant.now();
        TraceService.SpanHandle span = traceService == null ? TraceService.SpanHandle.unavailable()
                : traceService.startSpan("FALLBACK".equals(persistedRunType) ? SpanType.FALLBACK : SpanType.MODEL,
                persistedRunType, Map.of("provider", provider.name(), "promptVersion", promptVersion));
        AgentProviderResponse response = null;
        try {
            response = provider.invoke(request);
            JsonNode parsed = objectMapper.readTree(response.rawText());
            validateResponse(request.operation(), parsed);
            if (traceService != null) {
                Map<String, Object> outputSnapshot = new LinkedHashMap<>();
                outputSnapshot.put("operation", request.operation());
                outputSnapshot.put("provider", provider.name());
                outputSnapshot.put("model", response.model());
                outputSnapshot.put("promptVersion", promptVersion);
                outputSnapshot.put("output", parsed);
                traceService.recordCurrentSnapshot(TraceSnapshotType.MODEL_OUTPUT,
                        "MODEL_OPERATION", request.operation(), outputSnapshot);
            }
            record(sessionId, workflowId, inputEventIds, persistedRunType, provider.name(), response.model(),
                    promptVersion, requestJson, responseJson(response.rawText()), response.inputTokens(),
                    response.outputTokens(), startedAt, "SUCCEEDED", null);
            if (traceService != null) traceService.finishSpanSuccess(span, null, Map.of(
                    "provider", provider.name(), "model", response.model(), "promptVersion", promptVersion));
            return parsed;
        } catch (BusinessException ex) {
            record(sessionId, workflowId, inputEventIds, persistedRunType, provider.name(), model(response),
                    promptVersion, requestJson, responseJson(rawText(response)), inputTokens(response),
                    outputTokens(response), startedAt, "FAILED_GENERATION", ex.getResultCode().code());
            if (traceService != null) traceService.finishSpanFailure(span, ex.getResultCode().code(), ex, Map.of("provider", provider.name()));
            throw ex;
        } catch (JsonProcessingException ex) {
            record(sessionId, workflowId, inputEventIds, persistedRunType, provider.name(), model(response),
                    promptVersion, requestJson, responseJson(rawText(response)), inputTokens(response),
                    outputTokens(response), startedAt, "FAILED_GENERATION", "INVALID_PROVIDER_JSON");
            if (traceService != null) traceService.finishSpanFailure(span, "INVALID_PROVIDER_JSON", ex, Map.of("provider", provider.name()));
            throw new IllegalStateException("INVALID_PROVIDER_JSON");
        } catch (RuntimeException ex) {
            record(sessionId, workflowId, inputEventIds, persistedRunType, provider.name(), model(response),
                    promptVersion, requestJson, responseJson(rawText(response)), inputTokens(response),
                    outputTokens(response), startedAt, "FAILED_GENERATION", safeErrorCode(ex));
            if (traceService != null) traceService.finishSpanFailure(span, safeErrorCode(ex), ex, Map.of("provider", provider.name()));
            throw ex;
        }
    }

    private void record(
            Long sessionId, Long workflowId, List<Long> inputEventIds, String runType, String provider,
            String model, String promptVersion, String requestJson, String responseJson,
            Integer inputTokens, Integer outputTokens, Instant startedAt, String status, String errorCode
    ) {
        agentRunService.recordModelCall(
                sessionId, workflowId, runType, provider, model, promptVersion, inputEventIds,
                requestJson, responseJson, inputTokens, outputTokens,
                Duration.between(startedAt, Instant.now()).toMillis(), status, errorCode
        );
    }

    private void validateResponse(String operation, JsonNode parsed) {
        if (parsed == null || !parsed.isObject()) {
            throw new IllegalStateException("INVALID_PROVIDER_JSON");
        }
        String requiredField = "INTENT_CLASSIFICATION".equals(operation) ? "intent" : "answer";
        String requiredValue = nullableText(parsed, requiredField);
        if (requiredValue == null || requiredValue.isBlank()) {
            throw new IllegalStateException("INVALID_PROVIDER_JSON");
        }
    }

    private String requiredText(JsonNode node, String field) {
        String value = nullableText(node, field);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("INVALID_PROVIDER_JSON");
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
            throw new IllegalStateException("AGENT_PROVIDER_REQUEST_SERIALIZATION_FAILED");
        }
    }

    private String responseJson(String rawText) {
        return toJson(Map.of("rawText", rawText == null ? "" : rawText));
    }

    private static String rawText(AgentProviderResponse response) {
        return response == null ? null : response.rawText();
    }

    private static String model(AgentProviderResponse response) {
        return response == null ? null : response.model();
    }

    private static Integer inputTokens(AgentProviderResponse response) {
        return response == null ? null : response.inputTokens();
    }

    private static Integer outputTokens(AgentProviderResponse response) {
        return response == null ? null : response.outputTokens();
    }

    private static String safeErrorCode(RuntimeException ex) {
        String message = ex.getMessage();
        return message != null && message.matches("[A-Z0-9_]+") ? message : "AGENT_PROVIDER_FAILED";
    }
}
