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
import travelcare_agent.agentrun.entity.AgentRun;
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
import java.util.Locale;
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
        return classifyIntentAndExtractSlots(sessionId, workflowId, inputEventIds, List.of(), message);
    }

    public MockIntentClassifier.IntentResult classifyIntentAndExtractSlots(
            Long sessionId,
            Long workflowId,
            List<Long> inputEventIds,
            List<Long> retrievalContextIds,
            String message
    ) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("message", message);
        JsonNode output = invokeStructured(
                sessionId, workflowId, inputEventIds, retrievalContextIds,
                "INTENT_CLASSIFICATION", PromptTemplateService.INTENT_CLASSIFIER_V1, input
        );
        return new MockIntentClassifier.IntentResult(
                requiredText(output, "intent"), nullableText(output, "orderNo")
        );
    }

    public String generateCustomerAnswer(
            Long sessionId,
            Long workflowId,
            List<Long> inputEventIds,
            String deterministicAnswer
    ) {
        return generateCustomerAnswer(sessionId, workflowId, inputEventIds, List.of(), deterministicAnswer);
    }

    public String generateCustomerAnswer(
            Long sessionId,
            Long workflowId,
            List<Long> inputEventIds,
            List<Long> retrievalContextIds,
            String deterministicAnswer
    ) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("deterministicAnswer", deterministicAnswer);
        JsonNode output = invokeStructured(
                sessionId, workflowId, inputEventIds, retrievalContextIds,
                "RESPONSE_GENERATION", PromptTemplateService.RESPONSE_GENERATOR_V1, input
        );
        return requiredText(output, "answer");
    }

    private JsonNode invokeStructured(
            Long sessionId,
            Long workflowId,
            List<Long> inputEventIds,
            List<Long> retrievalContextIds,
            String operation,
            String promptVersion,
        Map<String, Object> input
    ) {
        Instant startedAt = Instant.now();
        String requestHash = agentRunService.hashCanonical(Map.of(
                "operation", operation,
                "model", properties.getModel(),
                "promptVersion", promptVersion,
                "input", input
        ));
        AgentRun run = agentRunService.startModelCall(new AgentRunService.ModelCallStart(
                sessionId, workflowId, operation, providerMode(), primaryProvider.providerName(),
                properties.getModel(), promptVersion, safeList(inputEventIds),
                safeList(retrievalContextIds), requestHash
        ));

        ModelRequest request;
        try {
            String inputJson = toJson(input);
            String prompt = promptTemplateService.render(promptVersion, inputJson);
            Map<String, Object> metadata = new LinkedHashMap<>(input);
            metadata.put("operation", operation);
            request = new ModelRequest(
                    properties.getModel(), promptVersion,
                    List.of(new ModelMessage("user", prompt)), properties.getTemperature(),
                    properties.getTimeoutMs(), metadata
            );
            travelcare_agent.dryrun.SideEffectGuard.checkCurrent(
                    travelcare_agent.dryrun.SideEffectOperation.EXTERNAL_PROVIDER_CALL);
        } catch (RuntimeException ex) {
            ModelCallException mapped = ex instanceof ModelCallException modelError
                    ? modelError
                    : new ModelCallException("MODEL_REQUEST_PREPARATION_FAILED", "Model request preparation failed", ex);
            complete(run, startedAt, null, UsageTotals.empty(), false, "FAILED",
                    mapped.code(), safePrimaryFailure(mapped.code()), null, null);
            throw mapped;
        }

        recordModelInputTrace(operation, promptVersion, input);
        AttemptResult primary;
        try {
            primary = invokeAttempt(primaryProvider, request, operation, promptVersion, false);
        } catch (AttemptFailure primaryFailure) {
            return invokeFallback(run, startedAt, request, operation, promptVersion, primaryFailure);
        }

        complete(run, startedAt, primary.parsed(), UsageTotals.from(primary.response().usage()), false,
                "SUCCESS", null, null, null, null);
        return primary.parsed();
    }

    private JsonNode invokeFallback(
            AgentRun run,
            Instant startedAt,
            ModelRequest request,
            String operation,
            String promptVersion,
            AttemptFailure primaryFailure
    ) {
        TraceContextHolder.TraceContext context = TraceContextHolder.current();
        if (traceService != null && context != null) {
            traceService.recordEvent(context.traceId(), context.spanId(), TraceEventType.FALLBACK,
                    "model-provider-fallback", Map.of("provider", fallbackProvider.providerName()));
        }
        UsageTotals primaryUsage = UsageTotals.from(usage(primaryFailure.response));
        try {
            AttemptResult fallback = invokeAttempt(fallbackProvider, request, operation, promptVersion, true);
            UsageTotals totals = primaryUsage.plus(UsageTotals.from(fallback.response().usage()));
            complete(run, startedAt, fallback.parsed(), totals, true, "FALLBACK_SUCCESS",
                    primaryFailure.error.code(), safePrimaryFailure(primaryFailure.error.code()),
                    fallbackProvider.providerName(), fallback.response().model());
            return fallback.parsed();
        } catch (AttemptFailure fallbackFailure) {
            UsageTotals totals = primaryUsage.plus(UsageTotals.from(usage(fallbackFailure.response)));
            complete(run, startedAt, null, totals, true, "FALLBACK_FAILED", "MODEL_FALLBACK_FAILED",
                    safeFallbackFailure(primaryFailure.error.code(), fallbackFailure.error.code()),
                    fallbackProvider.providerName(), model(fallbackFailure.response));
            throw new ModelCallException(
                    "MODEL_FALLBACK_FAILED", "Deterministic model fallback failed", fallbackFailure.error);
        }
    }

    private AttemptResult invokeAttempt(
            ChatModelProvider provider,
            ModelRequest request,
            String operation,
            String promptVersion,
            boolean fallback
    ) {
        TraceService.SpanHandle span = traceService == null ? TraceService.SpanHandle.unavailable()
                : traceService.startSpan(fallback ? SpanType.FALLBACK : SpanType.MODEL,
                operation, Map.of("provider", provider.providerName(), "promptVersion", promptVersion));
        ModelResponse response = null;
        try {
            response = provider.call(request);
            if (response == null || response.content() == null || response.content().isBlank()) {
                throw new ModelCallException("MODEL_EMPTY_RESPONSE", "Model returned empty content");
            }
            JsonNode parsed = objectMapper.readTree(response.content());
            validateResponse(operation, parsed);
            recordModelOutputTrace(operation, promptVersion, provider, response, parsed);
            if (traceService != null) {
                traceService.finishSpanSuccess(span, null, Map.of(
                        "provider", provider.providerName(), "model", safeValue(response.model()),
                        "promptVersion", promptVersion));
            }
            return new AttemptResult(parsed, response);
        } catch (JsonProcessingException ex) {
            ModelCallException mapped = new ModelCallException(
                    "MODEL_INVALID_RESPONSE", "Model response was not valid JSON", ex);
            finishAttemptFailure(span, provider, mapped);
            throw new AttemptFailure(mapped, response);
        } catch (ModelCallException ex) {
            finishAttemptFailure(span, provider, ex);
            throw new AttemptFailure(ex, response);
        } catch (RuntimeException ex) {
            ModelCallException mapped = new ModelCallException(
                    "MODEL_PROVIDER_FAILED", "Model provider failed", ex);
            finishAttemptFailure(span, provider, mapped);
            throw new AttemptFailure(mapped, response);
        }
    }

    private void complete(
            AgentRun run,
            Instant startedAt,
            JsonNode response,
            UsageTotals usage,
            boolean fallbackUsed,
            String status,
            String errorCode,
            String safeErrorSummary,
            String fallbackProviderName,
            String fallbackModel
    ) {
        agentRunService.completeModelCall(run, new AgentRunService.ModelCallCompletion(
                fallbackProviderName, fallbackModel,
                response == null ? null : agentRunService.hashCanonical(response),
                usage.inputTokens(), usage.outputTokens(), usage.totalTokens(), fallbackUsed,
                Duration.between(startedAt, Instant.now()).toMillis(), status, errorCode, safeErrorSummary
        ));
    }

    private void recordModelInputTrace(String operation, String promptVersion, Map<String, Object> input) {
        if (traceService != null) {
            traceService.recordCurrentSnapshot(TraceSnapshotType.MODEL_INPUT,
                    "MODEL_OPERATION", operation, Map.of(
                            "operation", operation, "promptVersion", promptVersion, "input", input));
        }
    }

    private void recordModelOutputTrace(
            String operation,
            String promptVersion,
            ChatModelProvider provider,
            ModelResponse response,
            JsonNode parsed
    ) {
        if (traceService == null) {
            return;
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("operation", operation);
        snapshot.put("provider", provider.providerName());
        snapshot.put("model", response.model());
        snapshot.put("promptVersion", promptVersion);
        snapshot.put("output", parsed);
        traceService.recordCurrentSnapshot(
                TraceSnapshotType.MODEL_OUTPUT, "MODEL_OPERATION", operation, snapshot);
    }

    private void finishAttemptFailure(
            TraceService.SpanHandle span,
            ChatModelProvider provider,
            ModelCallException error
    ) {
        if (traceService != null) {
            traceService.finishSpanFailure(
                    span, error.code(), error, Map.of("provider", provider.providerName()));
        }
    }

    private void validateResponse(String operation, JsonNode parsed) {
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
            throw new ModelCallException(
                    "MODEL_REQUEST_SERIALIZATION_FAILED", "Model request serialization failed", ex);
        }
    }

    private String providerMode() {
        return properties.getProvider().name().toLowerCase(Locale.ROOT);
    }

    private static List<Long> safeList(List<Long> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static ModelUsage usage(ModelResponse response) {
        return response == null ? null : response.usage();
    }

    private static String model(ModelResponse response) {
        return response == null ? null : response.model();
    }

    private static String safePrimaryFailure(String code) {
        return "Primary model attempt failed: " + safeCode(code);
    }

    private static String safeFallbackFailure(String primaryCode, String fallbackCode) {
        return safePrimaryFailure(primaryCode)
                + "; fallback model attempt failed: " + safeCode(fallbackCode);
    }

    private static String safeCode(String value) {
        return value != null && value.matches("[A-Z0-9_]{1,64}") ? value : "UNKNOWN";
    }

    private static String safeValue(String value) {
        return value == null ? "" : value;
    }

    private record AttemptResult(JsonNode parsed, ModelResponse response) {
    }

    private static final class AttemptFailure extends RuntimeException {
        private final ModelCallException error;
        private final ModelResponse response;

        private AttemptFailure(ModelCallException error, ModelResponse response) {
            super(error);
            this.error = error;
            this.response = response;
        }
    }

    private record UsageTotals(Integer inputTokens, Integer outputTokens, Integer totalTokens) {
        private static UsageTotals empty() {
            return new UsageTotals(null, null, null);
        }

        private static UsageTotals from(ModelUsage usage) {
            return usage == null
                    ? empty()
                    : new UsageTotals(usage.inputTokens(), usage.outputTokens(), usage.totalTokens());
        }

        private UsageTotals plus(UsageTotals other) {
            return new UsageTotals(
                    sum(inputTokens, other.inputTokens),
                    sum(outputTokens, other.outputTokens),
                    sum(totalTokens, other.totalTokens)
            );
        }

        private static Integer sum(Integer first, Integer second) {
            if (first == null && second == null) {
                return null;
            }
            return (first == null ? 0 : first) + (second == null ? 0 : second);
        }
    }
}
