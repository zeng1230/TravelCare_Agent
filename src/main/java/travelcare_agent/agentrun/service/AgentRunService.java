package travelcare_agent.agentrun.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import travelcare_agent.agentrun.entity.AgentRun;
import travelcare_agent.agentrun.repository.AgentRunRepository;
import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.common.result.ResultCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import travelcare_agent.trace.TraceContextHolder;
import java.util.HexFormat;

@Service
public class AgentRunService {

    private static final Logger log = LoggerFactory.getLogger(AgentRunService.class);

    public static final String PROMPT_VERSION = "mock-agent-v1";
    public static final String RESPONSE_TEMPLATE_VERSION = "refund-inquiry-template-v1";

    private final AgentRunRepository repository;
    private final ObjectMapper canonicalMapper;

    public AgentRunService(AgentRunRepository repository) {
        this.repository = repository;
        this.canonicalMapper = JsonMapper.builder()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .build();
    }

    @Transactional
    public AgentRun startRun(
            Long sessionId,
            Long workflowId,
            Long taskId,
            String correlationId,
            String runType,
            String source,
            String createdBy
    ) {
        travelcare_agent.dryrun.SideEffectGuard.checkCurrent(travelcare_agent.dryrun.SideEffectOperation.AGENT_RUN_WRITE);
        LocalDateTime now = LocalDateTime.now();
        AgentRun run = new AgentRun();
        run.setSessionId(sessionId);
        run.setWorkflowId(workflowId);
        run.setTaskId(taskId);
        run.setCorrelationId(correlationId);
        run.setRunType(runType);
        run.setSource(source);
        run.setInputEventIdsJson("[]");
        run.setRetrievalChunkIdsJson("[]");
        run.setMemoryIdsJson("[]");
        run.setPromptVersion(PROMPT_VERSION);
        run.setResponseTemplateVersion(RESPONSE_TEMPLATE_VERSION);
        run.setStatus("RUNNING");
        run.setCreatedBy(createdBy);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        TraceContextHolder.TraceContext trace = TraceContextHolder.current();
        if (trace != null) { run.setTraceId(trace.traceId()); run.setSpanId(trace.spanId()); }
        return repository.save(run);
    }

    @Transactional
    public AgentRun startModelCall(ModelCallStart start) {
        try {
            travelcare_agent.dryrun.SideEffectGuard.checkCurrent(
                    travelcare_agent.dryrun.SideEffectOperation.AGENT_RUN_WRITE);
            LocalDateTime now = LocalDateTime.now();
            AgentRun run = new AgentRun();
            run.setSessionId(start.sessionId());
            run.setWorkflowId(start.workflowId());
            run.setRunType(start.operation());
            run.setSource("agent_model_service");
            run.setProviderMode(start.providerMode());
            run.setProvider(start.provider());
            run.setModel(start.model());
            run.setPromptVersion(start.promptVersion());
            run.setResponseTemplateVersion(start.promptVersion());
            run.setInputEventIdsJson(toJsonArray(start.inputEventIds()));
            run.setRetrievalChunkIdsJson(toJsonArray(start.retrievalContextIds()));
            run.setMemoryIdsJson("[]");
            run.setRequestHash(start.requestHash());
            run.setFallbackUsed(false);
            run.setStatus("RUNNING");
            run.setCreatedBy("AGENT_PROVIDER");
            run.setCreatedAt(now);
            run.setUpdatedAt(now);
            TraceContextHolder.TraceContext trace = TraceContextHolder.current();
            if (trace != null) { run.setTraceId(trace.traceId()); run.setSpanId(trace.spanId()); }
            return repository.save(run);
        } catch (RuntimeException ex) {
            log.warn("Agent run start persistence failed for operation={}", safeCode(start.operation()));
            return null;
        }
    }

    @Transactional
    public void completeModelCall(AgentRun run, ModelCallCompletion completion) {
        if (run == null) {
            return;
        }
        try {
            run.setFallbackProvider(completion.fallbackProvider());
            run.setFallbackModel(completion.fallbackModel());
            run.setResponseHash(completion.responseHash());
            run.setInputTokens(completion.inputTokens());
            run.setOutputTokens(completion.outputTokens());
            run.setTotalTokens(completion.totalTokens());
            run.setFallbackUsed(completion.fallbackUsed());
            run.setLatencyMs(Math.max(0L, completion.latencyMs()));
            run.setStatus(completion.status());
            run.setErrorCode(safeNullableCode(completion.errorCode()));
            run.setErrorMessage(safeSummary(completion.errorMessageSanitized()));
            repository.save(run);
        } catch (RuntimeException ex) {
            log.warn("Agent run completion persistence failed for operation={}", safeCode(run.getRunType()));
        }
    }

    @Transactional
    public AgentRun attachWorkflow(Long agentRunId, Long workflowId) {
        travelcare_agent.dryrun.SideEffectGuard.checkCurrent(travelcare_agent.dryrun.SideEffectOperation.AGENT_RUN_WRITE);
        AgentRun run = requireRun(agentRunId);
        run.setWorkflowId(workflowId);
        return repository.save(run);
    }

    @Transactional
    public AgentRun markContextReady(
            Long agentRunId,
            List<Long> inputEventIds,
            List<Long> retrievalChunkIds,
            List<Long> memoryIds,
            String workflowSnapshotJson,
            String promptVersion,
            String responseTemplateVersion
    ) {
        travelcare_agent.dryrun.SideEffectGuard.checkCurrent(travelcare_agent.dryrun.SideEffectOperation.AGENT_RUN_WRITE);
        AgentRun run = requireRun(agentRunId);
        run.setInputEventIdsJson(toJsonArray(inputEventIds));
        run.setRetrievalChunkIdsJson(toJsonArray(retrievalChunkIds));
        run.setMemoryIdsJson(toJsonArray(memoryIds));
        run.setWorkflowSnapshotJson(workflowSnapshotJson);
        run.setPromptVersion(defaultIfBlank(promptVersion, PROMPT_VERSION));
        run.setResponseTemplateVersion(defaultIfBlank(responseTemplateVersion, RESPONSE_TEMPLATE_VERSION));
        String contextHash = sha256(canonicalContext(run, workflowSnapshotJson));
        run.setContextHash(contextHash);
        run.setContextSnapshotHash(contextHash);
        return repository.save(run);
    }

    @Transactional
    public AgentRun markSucceeded(Long agentRunId, Long outputEventId, String answer) {
        travelcare_agent.dryrun.SideEffectGuard.checkCurrent(travelcare_agent.dryrun.SideEffectOperation.AGENT_RUN_WRITE);
        AgentRun run = requireRun(agentRunId);
        if (isTerminal(run)) {
            return run;
        }
        run.setOutputEventId(outputEventId);
        run.setAnswerHash(sha256(answer == null ? "" : answer));
        run.setStatus("SUCCEEDED");
        run.setLatencyMs(latencyMs(run));
        run.setErrorCode(null);
        run.setErrorMessage(null);
        return repository.save(run);
    }

    @Transactional
    public AgentRun markFailed(Long agentRunId, String status, String errorCode, Throwable error) {
        travelcare_agent.dryrun.SideEffectGuard.checkCurrent(travelcare_agent.dryrun.SideEffectOperation.AGENT_RUN_WRITE);
        AgentRun run = requireRun(agentRunId);
        if (isTerminal(run)) {
            return run;
        }
        run.setStatus(defaultIfBlank(status, "FAILED_GENERATION"));
        run.setErrorCode(defaultIfBlank(errorCode, "AGENT_RUN_ERROR"));
        run.setErrorMessage(safeErrorMessage(error));
        run.setLatencyMs(latencyMs(run));
        return repository.save(run);
    }

    public AgentRun getRun(Long agentRunId) {
        return repository.findById(agentRunId)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "AgentRun not found: " + agentRunId));
    }

    public AgentRunPage listRunsBySession(Long sessionId, long pageNo, long pageSize) {
        long safePageNo = pageNo <= 0 ? 1 : pageNo;
        long safePageSize = pageSize <= 0 ? 20 : Math.min(pageSize, 100);
        return new AgentRunPage(
                repository.findBySessionId(sessionId, safePageNo, safePageSize),
                repository.countBySessionId(sessionId),
                safePageNo,
                safePageSize
        );
    }

    public static String workflowSnapshotJson(
            Long workflowId,
            String status,
            String currentStep,
            Long version,
            String stateJson
    ) {
        String stateHash = sha256Static(stateJson == null ? "{}" : stateJson);
        return "{\"workflowId\":" + value(workflowId)
                + ",\"status\":\"" + escape(status)
                + "\",\"currentStep\":\"" + escape(currentStep)
                + "\",\"version\":" + value(version)
                + ",\"stateHash\":\"" + stateHash + "\"}";
    }

    private AgentRun requireRun(Long agentRunId) {
        return repository.findById(agentRunId)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "AgentRun not found: " + agentRunId));
    }

    private String canonicalContext(AgentRun run, String workflowSnapshotJson) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("sessionId", run.getSessionId());
        context.put("workflowId", run.getWorkflowId());
        context.put("taskId", run.getTaskId());
        context.put("runType", run.getRunType());
        context.put("inputEventIds", parseJson(run.getInputEventIdsJson()));
        context.put("retrievalChunkIds", parseJson(run.getRetrievalChunkIdsJson()));
        context.put("memoryIds", parseJson(run.getMemoryIdsJson()));
        context.put("workflowSnapshot", parseJson(workflowSnapshotJson));
        context.put("promptVersion", run.getPromptVersion());
        context.put("responseTemplateVersion", run.getResponseTemplateVersion());
        try {
            return canonicalMapper.writeValueAsString(context);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to canonicalize agent context", ex);
        }
    }

    private Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return canonicalMapper.readValue(json, Object.class);
        } catch (JsonProcessingException ex) {
            return json;
        }
    }

    private String toJsonArray(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        try {
            return canonicalMapper.writeValueAsString(values);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize id array", ex);
        }
    }

    private long latencyMs(AgentRun run) {
        if (run.getCreatedAt() == null) {
            return 0L;
        }
        return Math.max(0L, ChronoUnit.MILLIS.between(run.getCreatedAt(), LocalDateTime.now()));
    }

    private boolean isTerminal(AgentRun run) {
        String status = run.getStatus();
        return "SUCCEEDED".equals(status)
                || "SUCCESS".equals(status)
                || "FAILED".equals(status)
                || "FALLBACK_SUCCESS".equals(status)
                || "FALLBACK_FAILED".equals(status)
                || (status != null && status.startsWith("FAILED_"));
    }

    private String safeErrorMessage(Throwable error) {
        if (error == null || error.getMessage() == null) {
            return null;
        }
        String message = error.getMessage().replace('\n', ' ').replace('\r', ' ');
        return message.length() <= 512 ? message : message.substring(0, 512);
    }

    public String hashCanonical(Object value) {
        try {
            return sha256(canonicalMapper.writeValueAsString(value));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to hash canonical model evidence", ex);
        }
    }

    private static String safeNullableCode(String value) {
        return value == null ? null : safeCode(value);
    }

    private static String safeCode(String value) {
        if (value == null || !value.matches("[A-Z0-9_]{1,64}")) {
            return "UNKNOWN";
        }
        return value;
    }

    private static String safeSummary(String value) {
        if (value == null) {
            return null;
        }
        String primaryOnly = "Primary model attempt failed: [A-Z0-9_]{1,64}";
        String primaryAndFallback = primaryOnly
                + "; fallback model attempt failed: [A-Z0-9_]{1,64}";
        return value.matches(primaryOnly) || value.matches(primaryAndFallback) ? value : null;
    }

    private String sha256(String value) {
        return sha256Static(value);
    }

    private static String sha256Static(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String value(Long value) {
        return value == null ? "null" : value.toString();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record AgentRunPage(List<AgentRun> records, long total, long pageNo, long pageSize) {
    }

    public record ModelCallStart(
            Long sessionId,
            Long workflowId,
            String operation,
            String providerMode,
            String provider,
            String model,
            String promptVersion,
            List<Long> inputEventIds,
            List<Long> retrievalContextIds,
            String requestHash
    ) {
    }

    public record ModelCallCompletion(
            String fallbackProvider,
            String fallbackModel,
            String responseHash,
            Integer inputTokens,
            Integer outputTokens,
            Integer totalTokens,
            boolean fallbackUsed,
            long latencyMs,
            String status,
            String errorCode,
            String errorMessageSanitized
    ) {
    }
}
