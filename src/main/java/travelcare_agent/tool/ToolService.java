package travelcare_agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.tool.entity.ToolCall;
import travelcare_agent.tool.repository.ToolCallRepository;
import travelcare_agent.reconciliation.ReconciliationService;
import travelcare_agent.observability.TravelCareMetrics;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.function.Supplier;
import java.util.Map;
import travelcare_agent.trace.*;

@Service
public class ToolService {

    private final ToolCallRepository toolCallRepository;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final TraceService traceService;
    private final ReconciliationService reconciliationService;
    private final TravelCareMetrics metrics;

    @Autowired
    public ToolService(ToolCallRepository toolCallRepository, IdempotencyService idempotencyService, TraceService traceService,
            @Autowired(required = false) ReconciliationService reconciliationService,
            @Autowired(required = false) TravelCareMetrics metrics) {
        this(toolCallRepository, idempotencyService, new ObjectMapper().findAndRegisterModules(), traceService,
                reconciliationService, metrics);
    }

    public ToolService(ToolCallRepository toolCallRepository, IdempotencyService idempotencyService) {
        this(toolCallRepository, idempotencyService, new ObjectMapper().findAndRegisterModules(), null, null);
    }

    ToolService(
            ToolCallRepository toolCallRepository,
            IdempotencyService idempotencyService,
            ObjectMapper objectMapper
    ) {
        this(toolCallRepository, idempotencyService, objectMapper, null, null);
    }

    ToolService(ToolCallRepository toolCallRepository, IdempotencyService idempotencyService,
            ObjectMapper objectMapper, TraceService traceService) {
        this(toolCallRepository, idempotencyService, objectMapper, traceService, null);
    }

    ToolService(ToolCallRepository toolCallRepository, IdempotencyService idempotencyService,
            ObjectMapper objectMapper, TraceService traceService, ReconciliationService reconciliationService) {
        this(toolCallRepository, idempotencyService, objectMapper, traceService, reconciliationService, null);
    }

    ToolService(ToolCallRepository toolCallRepository, IdempotencyService idempotencyService,
            ObjectMapper objectMapper, TraceService traceService, ReconciliationService reconciliationService,
            TravelCareMetrics metrics) {
        this.toolCallRepository = toolCallRepository;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
        this.traceService = traceService;
        this.reconciliationService = reconciliationService;
        this.metrics = metrics;
    }

    public <T> ToolExecution<T> execute(ToolCommand command, Class<T> resultType, Supplier<T> action) {
        travelcare_agent.dryrun.SideEffectGuard.checkCurrent(travelcare_agent.dryrun.SideEffectOperation.TOOL_CALL_WRITE);
        Instant startedAt = Instant.now();
        if (metrics != null) metrics.toolStarted(command.toolName(), command.sideEffectingExternalCall());
        TraceService.SpanHandle span = traceService == null ? TraceService.SpanHandle.unavailable()
                : traceService.startSpan(SpanType.TOOL, command.toolName(), Map.of("workflowId", command.workflowId()));
        if (traceService != null) traceService.recordCurrentSnapshot(TraceSnapshotType.TOOL_REQUEST,
                "TOOL", command.toolName(), snapshotMap(command.toolName(), command.workflowId(), command.requestJson(), null, null, null));
        IdempotencyService.Decision decision = idempotencyService.begin(
                "tool_call",
                command.idempotencyKey(),
                command.requestHash()
        );
        if (decision.reuse()) {
            ToolCall cached = toolCallRepository.findById(decision.resultId())
                    .orElseThrow(() -> new IllegalStateException("Cached tool call not found: " + decision.resultId()));
            if (traceService != null) {
                traceService.recordCurrentSnapshot(TraceSnapshotType.TOOL_RESULT, "TOOL_CALL",
                        String.valueOf(cached.getId()), snapshotMap(command.toolName(), command.workflowId(), null,
                                cached.getStatus().name(), true, deserialize(cached.getResponseJson(), resultType)));
                traceService.recordEvent(span.traceId(), span.spanId(), TraceEventType.IDEMPOTENCY_REUSED, command.toolName(), Map.of("toolCallId", cached.getId()));
                traceService.finishSpanSuccess(span, "TOOL_CALL:" + cached.getId(), Map.of("status", cached.getStatus().name(), "reused", true));
            }
            if (metrics != null) metrics.toolSkipped(command.toolName(), command.sideEffectingExternalCall(), cached.getStatus().name());
            return new ToolExecution<>(cached, deserialize(cached.getResponseJson(), resultType), true);
        }

        ToolCall pending = ToolCall.running(new ToolCall.ToolCommandFields(
                command.sessionId(),
                command.workflowId(),
                command.stepId(),
                command.toolName(),
                command.idempotencyKey(),
                command.requestHash(),
                command.requestJson(),
                command.timeoutAt()
        ));
        if (span.available()) { pending.setTraceId(span.traceId()); pending.setSpanId(span.spanId()); }
        ToolCall toolCall = toolCallRepository.save(pending);

        try {
            T result = action.get();
            toolCall.succeed(serialize(result));
            toolCallRepository.save(toolCall);
            idempotencyService.markSuccess(command.idempotencyKey(), "tool_call", toolCall.getId());
            if (traceService != null) traceService.recordCurrentSnapshot(TraceSnapshotType.TOOL_RESULT,
                    "TOOL_CALL", String.valueOf(toolCall.getId()), snapshotMap(command.toolName(), command.workflowId(), null,
                            toolCall.getStatus().name(), false, result));
            if (traceService != null) traceService.finishSpanSuccess(span, "TOOL_CALL:" + toolCall.getId(), Map.of("status", toolCall.getStatus().name()));
            if (metrics != null) metrics.toolCompleted(command.toolName(), command.sideEffectingExternalCall(),
                    Duration.between(startedAt, Instant.now()));
            return new ToolExecution<>(toolCall, result, false);
        } catch (BusinessException ex) {
            toolCall.fail(errorJson(ex.getResultCode().code(), ex.getMessage()), ex.getResultCode().code());
            toolCallRepository.save(toolCall);
            idempotencyService.markFailed(command.idempotencyKey());
            if (traceService != null) traceService.finishSpanFailure(span, ex.getResultCode().code(), ex, Map.of("toolCallId", toolCall.getId()));
            if (metrics != null) metrics.toolFailed(command.toolName(), command.sideEffectingExternalCall(),
                    ex.getResultCode().code(), Duration.between(startedAt, Instant.now()));
            throw ex;
        } catch (RuntimeException ex) {
            String errorCode = isTimeout(ex) ? "TOOL_TIMEOUT" : "UNKNOWN_TOOL_ERROR";
            if (command.sideEffectingExternalCall() && isTimeout(ex)) {
                toolCall.unknown(errorJson(errorCode, safeMessage(ex)), errorCode);
            } else {
                toolCall.fail(errorJson(errorCode, safeMessage(ex)), errorCode);
            }
            toolCallRepository.save(toolCall);
            if (toolCall.getStatus() == travelcare_agent.enums.ToolCallStatus.UNKNOWN && reconciliationService != null) {
                reconciliationService.createOrReusePending("tool_call", toolCall.getId(), errorCode, toolCall.getTraceId());
            }
            idempotencyService.markFailed(command.idempotencyKey());
            if (traceService != null) traceService.finishSpanFailure(span, errorCode, ex, Map.of("toolCallId", toolCall.getId()));
            if (metrics != null) {
                Duration duration = Duration.between(startedAt, Instant.now());
                if (toolCall.getStatus() == travelcare_agent.enums.ToolCallStatus.UNKNOWN) {
                    metrics.toolUnknown(command.toolName(), command.sideEffectingExternalCall(), errorCode, duration);
                } else {
                    metrics.toolFailed(command.toolName(), command.sideEffectingExternalCall(), errorCode, duration);
                }
            }
            throw ex;
        }
    }

    private <T> T deserialize(String responseJson, Class<T> resultType) {
        try {
            return objectMapper.readValue(responseJson, resultType);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to deserialize cached tool result", ex);
        }
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize tool result", ex);
        }
    }

    private String errorJson(String code, String message) {
        try {
            return objectMapper.writeValueAsString(new ToolError(code, message));
        } catch (JsonProcessingException ex) {
            return "{\"code\":\"" + code + "\"}";
        }
    }

    private static boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (current instanceof java.util.concurrent.TimeoutException
                    || current instanceof java.net.SocketTimeoutException
                    || (message != null && message.toLowerCase(java.util.Locale.ROOT).contains("timeout"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null ? throwable.getClass().getSimpleName() : message;
    }

    private static Map<String, Object> snapshotMap(String toolName, Long workflowId, String request,
            String status, Boolean reused, Object result) {
        Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("toolName", toolName);
        if (workflowId != null) value.put("workflowId", workflowId);
        if (request != null) value.put("request", request);
        if (status != null) value.put("status", status);
        if (reused != null) value.put("reused", reused);
        value.put("result", result);
        return value;
    }

    public record ToolCommand(
            Long sessionId,
            Long workflowId,
            Long stepId,
            String toolName,
            String idempotencyKey,
            String requestHash,
            String requestJson,
            LocalDateTime timeoutAt,
            boolean sideEffectingExternalCall
    ) {
        public ToolCommand(
                Long sessionId,
                Long workflowId,
                Long stepId,
                String toolName,
                String idempotencyKey,
                String requestHash,
                String requestJson,
                LocalDateTime timeoutAt) {
            this(sessionId, workflowId, stepId, toolName, idempotencyKey, requestHash, requestJson, timeoutAt, false);
        }
    }

    public record ToolExecution<T>(ToolCall toolCall, T result, boolean reused) {
    }

    private record ToolError(String code, String message) {
    }
}
