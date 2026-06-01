package travelcare_agent.agentrun.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import travelcare_agent.agentrun.entity.AgentRun;
import travelcare_agent.agentrun.repository.AgentRunRepository;
import travelcare_agent.audit.entity.AuditLog;
import travelcare_agent.audit.repository.AuditLogRepository;
import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.common.result.ResultCode;
import travelcare_agent.conversation.entity.SessionEvent;
import travelcare_agent.conversation.repository.SessionEventRepository;
import travelcare_agent.memory.entity.AgentMemory;
import travelcare_agent.memory.repository.AgentMemoryRepository;
import travelcare_agent.retrieval.entity.KnowledgeChunk;
import travelcare_agent.retrieval.repository.KnowledgeChunkRepository;
import travelcare_agent.workflow.entity.Workflow;
import travelcare_agent.workflow.repository.WorkflowRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AgentRunReplayService {

    private static final int PREVIEW_LIMIT = 200;

    private final AgentRunRepository agentRunRepository;
    private final SessionEventRepository sessionEventRepository;
    private final KnowledgeChunkRepository knowledgeChunkRepository;
    private final AgentMemoryRepository agentMemoryRepository;
    private final WorkflowRepository workflowRepository;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentRunReplayService(
            AgentRunRepository agentRunRepository,
            SessionEventRepository sessionEventRepository,
            KnowledgeChunkRepository knowledgeChunkRepository,
            AgentMemoryRepository agentMemoryRepository,
            WorkflowRepository workflowRepository,
            AuditLogRepository auditLogRepository
    ) {
        this.agentRunRepository = agentRunRepository;
        this.sessionEventRepository = sessionEventRepository;
        this.knowledgeChunkRepository = knowledgeChunkRepository;
        this.agentMemoryRepository = agentMemoryRepository;
        this.workflowRepository = workflowRepository;
        this.auditLogRepository = auditLogRepository;
    }

    public AgentRunReplayResponse replay(Long agentRunId) {
        AgentRun run = agentRunRepository.findById(agentRunId)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "AgentRun not found: " + agentRunId));

        List<String> warnings = new java.util.ArrayList<>();
        List<Long> inputEventIds = parseIds(run.getInputEventIdsJson());
        List<Long> retrievalChunkIds = parseIds(run.getRetrievalChunkIdsJson());
        List<Long> memoryIds = parseIds(run.getMemoryIdsJson());

        List<SessionEvent> sessionEvents = sessionEventRepository.findBySessionIdOrderBySeqNo(run.getSessionId());
        Map<Long, SessionEvent> eventsById = sessionEvents.stream()
                .collect(Collectors.toMap(SessionEvent::getId, Function.identity(), (left, right) -> left));

        List<InputEventReplay> inputEvents = inputEventIds.stream()
                .map(id -> {
                    SessionEvent event = eventsById.get(id);
                    if (event == null) {
                        warnings.add("Missing input event: " + id);
                        return null;
                    }
                    return InputEventReplay.from(event);
                })
                .filter(Objects::nonNull)
                .toList();

        List<RetrievalChunkReplay> retrievalChunks = retrievalChunkIds.stream()
                .map(id -> {
                    Optional<KnowledgeChunk> chunk = knowledgeChunkRepository.findById(id);
                    if (chunk.isEmpty()) {
                        warnings.add("Missing retrieval chunk: " + id);
                        return null;
                    }
                    return RetrievalChunkReplay.from(chunk.get());
                })
                .filter(Objects::nonNull)
                .toList();

        List<MemoryReplay> memories = memoryIds.stream()
                .map(id -> {
                    Optional<AgentMemory> memory = agentMemoryRepository.findById(id);
                    if (memory.isEmpty()) {
                        warnings.add("Missing memory: " + id);
                        return null;
                    }
                    return MemoryReplay.from(memory.get());
                })
                .filter(Objects::nonNull)
                .toList();

        OutputAssistantEventReplay outputAssistantEvent = null;
        if (run.getOutputEventId() != null) {
            SessionEvent outputEvent = eventsById.get(run.getOutputEventId());
            if (outputEvent == null) {
                warnings.add("Missing output assistant event: " + run.getOutputEventId());
            } else {
                outputAssistantEvent = OutputAssistantEventReplay.from(outputEvent);
            }
        }

        WorkflowReplay workflowSnapshot = new WorkflowReplay(
                run.getWorkflowSnapshotJson(),
                currentWorkflow(run.getWorkflowId(), warnings)
        );

        List<AuditActionReplay> auditActions = auditActions(run);
        List<RelatedTaskAttempt> relatedTaskAttempts = relatedTaskAttempts(run);

        return new AgentRunReplayResponse(
                AgentRunReplaySummary.from(run),
                inputEvents,
                retrievalChunks,
                memories,
                workflowSnapshot,
                outputAssistantEvent,
                auditActions,
                relatedTaskAttempts,
                List.copyOf(warnings)
        );
    }

    private CurrentWorkflowReplay currentWorkflow(Long workflowId, List<String> warnings) {
        if (workflowId == null) {
            return null;
        }
        Optional<Workflow> workflow = workflowRepository.findById(workflowId);
        if (workflow.isEmpty()) {
            warnings.add("Missing current workflow: " + workflowId);
            return null;
        }
        return CurrentWorkflowReplay.from(workflow.get());
    }

    private List<AuditActionReplay> auditActions(AgentRun run) {
        Set<Long> seen = new LinkedHashSet<>();
        java.util.ArrayList<AuditLog> logs = new java.util.ArrayList<>();
        for (AuditLog log : auditLogRepository.findBySessionId(run.getSessionId())) {
            if (log.getId() != null && seen.add(log.getId())) {
                logs.add(log);
            }
        }
        if (run.getWorkflowId() != null) {
            for (AuditLog log : auditLogRepository.findByWorkflowId(run.getWorkflowId())) {
                if (log.getId() != null && seen.add(log.getId())) {
                    logs.add(log);
                }
            }
        }
        return logs.stream()
                .sorted(Comparator.comparing(AuditLog::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(AuditLog::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(AuditActionReplay::from)
                .toList();
    }

    private List<RelatedTaskAttempt> relatedTaskAttempts(AgentRun run) {
        if (run.getTaskId() == null) {
            return List.of();
        }
        return agentRunRepository.findAll().stream()
                .filter(candidate -> run.getTaskId().equals(candidate.getTaskId()))
                .sorted(Comparator.comparing(AgentRun::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(AgentRun::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(RelatedTaskAttempt::from)
                .toList();
    }

    private List<Long> parseIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static String preview(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() <= PREVIEW_LIMIT) {
            return value;
        }
        return value.substring(0, PREVIEW_LIMIT);
    }

    public record AgentRunReplayResponse(
            AgentRunReplaySummary agentRun,
            List<InputEventReplay> inputEvents,
            List<RetrievalChunkReplay> retrievalChunks,
            List<MemoryReplay> memories,
            WorkflowReplay workflowSnapshot,
            OutputAssistantEventReplay outputAssistantEvent,
            List<AuditActionReplay> auditActions,
            List<RelatedTaskAttempt> relatedTaskAttempts,
            List<String> warnings
    ) {
    }

    public record AgentRunReplaySummary(
            Long id,
            Long sessionId,
            Long workflowId,
            Long taskId,
            String runType,
            String source,
            String status,
            String promptVersion,
            String responseTemplateVersion,
            String contextHash,
            String answerHash,
            Long outputEventId,
            Long latencyMs,
            String errorCode,
            String errorMessage,
            LocalDateTime createdAt
    ) {
        public static AgentRunReplaySummary from(AgentRun run) {
            return new AgentRunReplaySummary(
                    run.getId(),
                    run.getSessionId(),
                    run.getWorkflowId(),
                    run.getTaskId(),
                    run.getRunType(),
                    run.getSource(),
                    run.getStatus(),
                    run.getPromptVersion(),
                    run.getResponseTemplateVersion(),
                    run.getContextHash(),
                    run.getAnswerHash(),
                    run.getOutputEventId(),
                    run.getLatencyMs(),
                    run.getErrorCode(),
                    run.getErrorMessage(),
                    run.getCreatedAt()
            );
        }
    }

    public record InputEventReplay(
            Long eventId,
            Integer seqNo,
            String eventType,
            String role,
            String contentPreview,
            String metadataJson,
            LocalDateTime createdAt
    ) {
        static InputEventReplay from(SessionEvent event) {
            return new InputEventReplay(
                    event.getId(),
                    event.getSeqNo(),
                    event.getEventType() == null ? null : event.getEventType().name(),
                    event.getRole() == null ? null : event.getRole().name(),
                    preview(event.getContent()),
                    event.getMetadataJson(),
                    event.getCreatedAt()
            );
        }
    }

    public record RetrievalChunkReplay(
            Long chunkId,
            Long documentId,
            String title,
            String contentPreview,
            String sourceUri
    ) {
        static RetrievalChunkReplay from(KnowledgeChunk chunk) {
            return new RetrievalChunkReplay(
                    chunk.getId(),
                    chunk.getDocumentId(),
                    chunk.getTitle(),
                    preview(chunk.getContent()),
                    chunk.getSourceUri()
            );
        }
    }

    public record MemoryReplay(
            Long memoryId,
            String memoryType,
            String memoryKey,
            BigDecimal confidence,
            String status
    ) {
        static MemoryReplay from(AgentMemory memory) {
            return new MemoryReplay(
                    memory.getId(),
                    memory.getMemoryType() == null ? null : memory.getMemoryType().name(),
                    memory.getMemoryKey(),
                    memory.getConfidence(),
                    memory.getStatus()
            );
        }
    }

    public record WorkflowReplay(
            String agentRunSnapshotJson,
            CurrentWorkflowReplay currentWorkflow
    ) {
    }

    public record CurrentWorkflowReplay(
            Long workflowId,
            String status,
            String currentStep,
            Long version
    ) {
        static CurrentWorkflowReplay from(Workflow workflow) {
            return new CurrentWorkflowReplay(
                    workflow.getId(),
                    workflow.getStatus() == null ? null : workflow.getStatus().name(),
                    workflow.getCurrentStep(),
                    workflow.getVersion()
            );
        }
    }

    public record OutputAssistantEventReplay(
            Long eventId,
            Integer seqNo,
            String role,
            String contentPreview,
            String metadataJson,
            LocalDateTime createdAt
    ) {
        static OutputAssistantEventReplay from(SessionEvent event) {
            return new OutputAssistantEventReplay(
                    event.getId(),
                    event.getSeqNo(),
                    event.getRole() == null ? null : event.getRole().name(),
                    preview(event.getContent()),
                    event.getMetadataJson(),
                    event.getCreatedAt()
            );
        }
    }

    public record AuditActionReplay(
            Long auditLogId,
            String action,
            String targetType,
            Long targetId,
            String evidenceJson,
            LocalDateTime createdAt
    ) {
        static AuditActionReplay from(AuditLog auditLog) {
            return new AuditActionReplay(
                    auditLog.getId(),
                    auditLog.getAction(),
                    auditLog.getTargetType(),
                    auditLog.getTargetId(),
                    auditLog.getEvidenceJson(),
                    auditLog.getCreatedAt()
            );
        }
    }

    public record RelatedTaskAttempt(
            Long agentRunId,
            Long taskId,
            String status,
            Long outputEventId,
            LocalDateTime createdAt
    ) {
        static RelatedTaskAttempt from(AgentRun run) {
            return new RelatedTaskAttempt(
                    run.getId(),
                    run.getTaskId(),
                    run.getStatus(),
                    run.getOutputEventId(),
                    run.getCreatedAt()
            );
        }
    }
}
