package travelcare_agent.conversation.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import travelcare_agent.agentrun.entity.AgentRun;
import travelcare_agent.agentrun.service.AgentRunService;
import travelcare_agent.agent.AgentOrchestrator;
import travelcare_agent.audit.AuditService;
import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.common.result.ResultCode;
import travelcare_agent.conversation.entity.Session;
import travelcare_agent.conversation.entity.SessionEvent;
import travelcare_agent.conversation.repository.SessionRepository;
import travelcare_agent.enums.SessionEventRole;
import travelcare_agent.tool.IdempotencyService;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import travelcare_agent.trace.SpanType;
import travelcare_agent.trace.TraceContextHolder;
import travelcare_agent.trace.TraceService;

@Service
public class SessionService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SessionService.class);

    private final SessionRepository repository;
    private final SessionEventService eventService;
    private final AgentOrchestrator agentOrchestrator;
    private final IdempotencyService idempotencyService;
    private final travelcare_agent.workflow.repository.WorkflowRepository workflowRepository;
    private final travelcare_agent.workflow.WorkflowTaskService workflowTaskService;
    private final travelcare_agent.workflow.repository.WorkflowTaskRepository workflowTaskRepository;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;
    private final AgentRunService agentRunService;
    private final TraceService traceService;

    @org.springframework.beans.factory.annotation.Autowired
    public SessionService(
            SessionRepository repository,
            SessionEventService eventService,
            AgentOrchestrator agentOrchestrator,
            IdempotencyService idempotencyService,
            travelcare_agent.workflow.repository.WorkflowRepository workflowRepository,
            travelcare_agent.workflow.WorkflowTaskService workflowTaskService,
            travelcare_agent.workflow.repository.WorkflowTaskRepository workflowTaskRepository,
            ObjectMapper objectMapper,
            AuditService auditService,
            AgentRunService agentRunService,
            TraceService traceService
    ) {
        this.repository = repository;
        this.eventService = eventService;
        this.agentOrchestrator = agentOrchestrator;
        this.idempotencyService = idempotencyService;
        this.workflowRepository = workflowRepository;
        this.workflowTaskService = workflowTaskService;
        this.workflowTaskRepository = workflowTaskRepository;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
        this.agentRunService = agentRunService;
        this.traceService = traceService;
    }

    public SessionService(SessionRepository repository, SessionEventService eventService,
            AgentOrchestrator agentOrchestrator, IdempotencyService idempotencyService,
            travelcare_agent.workflow.repository.WorkflowRepository workflowRepository,
            travelcare_agent.workflow.WorkflowTaskService workflowTaskService,
            travelcare_agent.workflow.repository.WorkflowTaskRepository workflowTaskRepository,
            ObjectMapper objectMapper, AuditService auditService, AgentRunService agentRunService) {
        this(repository, eventService, agentOrchestrator, idempotencyService, workflowRepository,
                workflowTaskService, workflowTaskRepository, objectMapper, auditService, agentRunService, null);
    }

    public CreateSessionResult createSession(Long userId, String channel) {
        travelcare_agent.dryrun.SideEffectGuard.checkCurrent(travelcare_agent.dryrun.SideEffectOperation.SESSION_WRITE);
        if (userId == null) {
            throw new BusinessException(ResultCode.VALIDATION_FAILED, "userId is required");
        }
        if (isBlank(channel)) {
            throw new BusinessException(ResultCode.VALIDATION_FAILED, "channel is required");
        }

        Session session = repository.save(Session.create(userId, channel.trim()));
        return new CreateSessionResult(session.getId(), session.getStatus().name());
    }

    @Transactional
    public SendMessageResult sendMessage(Long sessionId, String content, String idempotencyKey, Boolean async) {
        travelcare_agent.dryrun.SideEffectGuard.checkCurrent(travelcare_agent.dryrun.SideEffectOperation.SESSION_EVENT_WRITE);
        Session session = requireExistingSession(sessionId);
        if (isBlank(content)) {
            throw new BusinessException(ResultCode.VALIDATION_FAILED, "content is required");
        }

        String requestHash = sha256(sessionId + ":" + content + ":" + async);
        IdempotencyService.Decision decision = idempotencyService.begin("sendMessage", idempotencyKey, requestHash);
        if (decision.reuse()) {
            List<SessionEvent> events = eventService.listEvents(sessionId);
            SessionEvent userEvent = events.stream().filter(e -> e.getRole() == SessionEventRole.USER && e.getMetadataJson().contains(idempotencyKey)).findFirst().orElseThrow();
            SessionEvent assistantEvent = events.stream().filter(e -> e.getRole() == SessionEventRole.ASSISTANT && e.getSeqNo() > userEvent.getSeqNo()).findFirst().orElse(null);
            SessionEvent workflowEvent = events.stream().filter(e -> e.getEventType() == travelcare_agent.enums.SessionEventType.WORKFLOW && e.getSeqNo() > userEvent.getSeqNo()).findFirst().orElse(null);
            
            String answer = assistantEvent != null ? assistantEvent.getContent() : "ACCEPTED";
            Long assistantId = assistantEvent != null ? assistantEvent.getId() : null;
            Long workflowEvtId = workflowEvent != null ? workflowEvent.getId() : null;
            
            Long workflowId = null;
            Long taskId = null;
            // Since we don't have a direct query for taskId by correlationId,
            // we could parse it from workflowEvent metadata if it exists.
            if (workflowEvent != null && workflowEvent.getMetadataJson() != null) {
                // "workflowId":"123"
                String meta = workflowEvent.getMetadataJson();
                if (meta.contains("\"workflowId\":\"")) {
                    int start = meta.indexOf("\"workflowId\":\"") + 14;
                    int end = meta.indexOf("\"", start);
                    if (end > start) {
                        try { workflowId = Long.parseLong(meta.substring(start, end)); } catch (Exception ignore) {}
                    }
                }
            }
            if (assistantEvent != null && assistantEvent.getMetadataJson() != null) {
                String meta = assistantEvent.getMetadataJson();
                if (meta.contains("\"taskId\":\"")) {
                    int start = meta.indexOf("\"taskId\":\"") + 10;
                    int end = meta.indexOf("\"", start);
                    if (end > start) {
                        try { taskId = Long.parseLong(meta.substring(start, end)); } catch (Exception ignore) {}
                    }
                }
            }

            if (workflowId == null) {
                List<travelcare_agent.workflow.entity.Workflow> wfs = workflowRepository.findBySessionId(sessionId);
                if (!wfs.isEmpty()) {
                    workflowId = wfs.get(wfs.size() - 1).getId();
                }
            }
            if (workflowId != null && taskId == null) {
                taskId = workflowTaskRepository.findByWorkflowId(workflowId)
                        .map(travelcare_agent.workflow.entity.WorkflowTask::getId)
                        .orElse(null);
            }

            TraceService.RootTrace reusedTrace = startTrace(session, userEvent.getId(), content, true);
            if (reusedTrace.available()) {
                traceService.recordEvent(reusedTrace.traceId(), reusedTrace.rootSpanId(),
                        travelcare_agent.trace.TraceEventType.IDEMPOTENCY_REUSED, "sendMessage-reused", Map.of());
                traceService.finishRootRunSuccess(reusedTrace.traceId(), workflowId, assistantId, Map.of("answer", answer));
            }
            return new SendMessageResult(answer, userEvent.getId(), workflowEvtId, assistantId, workflowId, taskId,
                    reusedTrace.traceId(), reusedTrace.available());
        }

        TraceService.RootTrace trace = TraceService.RootTrace.unavailable();
        try {
            SessionEvent userEvent = eventService.appendMessage(
                    sessionId,
                    SessionEventRole.USER,
                    content,
                    metadata("idempotencyKey", idempotencyKey)
            );
            trace = startTrace(session, userEvent.getId(), content, false);
            if (trace.available()) {
                traceService.recordSnapshot(trace.traceId(), trace.rootSpanId(), "USER_INPUT", "SESSION_EVENT",
                        String.valueOf(userEvent.getId()), Map.of(
                                "sessionId", sessionId,
                                "userId", session.getUserId(),
                                "message", content
                        ));
            }

            if (Boolean.TRUE.equals(async)) {
                travelcare_agent.workflow.entity.Workflow workflow = travelcare_agent.workflow.entity.Workflow.create(sessionId, "order_refund_inquiry");
                workflowRepository.save(workflow);

                String payload = "{\"message\":\"" + escape(content) + "\",\"userEventId\":" + userEvent.getId()
                        + (trace.available() ? ",\"traceId\":\"" + trace.traceId() + "\",\"parentSpanId\":\"" + trace.rootSpanId() + "\"" : "") + "}";
                travelcare_agent.workflow.entity.WorkflowTask task = workflowTaskService.createTask(workflow.getId(), sessionId,
                        "order_refund_inquiry", payload, idempotencyKey, trace.traceId(), trace.rootSpanId());
                
                idempotencyService.markSuccess(idempotencyKey, "sendMessage", userEvent.getId());
                return new SendMessageResult("ACCEPTED", userEvent.getId(), null, null, workflow.getId(), task.getId(),
                        trace.traceId(), trace.available());
            }

            AgentRun agentRun = null;
            AgentOrchestrator.AgentReply reply;
            try (TraceContextHolder.Scope ignored = trace.available()
                    ? TraceContextHolder.attach(trace.traceId(), trace.rootSpanId()) : null) {
                agentRun = safeStartAgentRun(sessionId, null, null, idempotencyKey, "SYNC_REPLY", "session_service", "SYSTEM");
                reply = agentOrchestrator.handle(
                        new AgentOrchestrator.AgentRequest(sessionId, session.getUserId(), content)
                );
            } catch (AgentOrchestrator.AgentStageException ex) {
                safeMarkContextFromException(agentRun, List.of(userEvent.getId()), ex);
                safeMarkFailed(agentRun, ex.agentRunStatus(), ex.errorCode(), ex);
                throw ex;
            } catch (RuntimeException ex) {
                safeMarkFailed(agentRun, "FAILED_CONTEXT", "AGENT_CONTEXT_OR_GENERATION_FAILED", ex);
                throw ex;
            }

            safeMarkContextReady(
                    agentRun,
                    reply.workflowId(),
                    List.of(userEvent.getId()),
                    reply.eventIds(),
                    reply.retrievalChunkIds(),
                    reply.memoryIds()
            );
            recordStage3Audit(sessionId, reply.workflowId(), reply.documentIds(), reply.retrievalChunkIds(), reply.memoryIds(), reply.eventIds());

            SessionEvent workflowEvent;
            try {
                workflowEvent = eventService.appendWorkflowRequested(
                        sessionId,
                        metadata(
                                "workflowType", "order_refund_inquiry",
                                "workflowId", value(reply.workflowId()),
                                "intent", reply.intent(),
                                "orderNo", reply.orderNo(),
                                "workflowStatus", reply.workflowStatus()
                        )
                );
            } catch (RuntimeException ex) {
                safeMarkFailed(agentRun, "FAILED_OUTPUT_EVENT", "WORKFLOW_EVENT_WRITE_FAILED", ex);
                throw ex;
            }

            java.util.Map<String, Object> metaMap = new java.util.HashMap<>();
            metaMap.put("source", "agent_orchestrator");
            metaMap.put("retrievalChunkIds", reply.retrievalChunkIds());
            metaMap.put("memoryIds", reply.memoryIds());
            metaMap.put("answerabilityStatus", reply.answerabilityStatus());
            metaMap.put("answerabilityReasonCode", reply.answerabilityReasonCode());
            metaMap.put("requiredAction", reply.requiredAction());
            metaMap.put("citations", reply.citations());
            metaMap.put("rejectedCitationCandidates", reply.rejectedCitationCandidates());
            String metaJson = "{}";
            try {
                metaJson = objectMapper.writeValueAsString(metaMap);
            } catch (Exception ignore) {}

            SessionEvent assistantEvent;
            TraceService.SpanHandle outputSpan = trace.available()
                    ? traceService.startSpan(trace.traceId(), trace.rootSpanId(), SpanType.OUTPUT, "assistant-response", Map.of())
                    : TraceService.SpanHandle.unavailable();
            try {
                assistantEvent = eventService.appendMessage(
                        sessionId,
                        SessionEventRole.ASSISTANT,
                        reply.answer(),
                        metaJson
                );
                if (traceService != null) traceService.finishSpanSuccess(outputSpan, "SESSION_EVENT:" + assistantEvent.getId(), Map.of("answer", reply.answer()));
            } catch (RuntimeException ex) {
                if (traceService != null) traceService.finishSpanFailure(outputSpan, "ASSISTANT_EVENT_WRITE_FAILED", ex, Map.of());
                safeMarkFailed(agentRun, "FAILED_OUTPUT_EVENT", "ASSISTANT_EVENT_WRITE_FAILED", ex);
                throw ex;
            }

            safeMarkSucceeded(agentRun, assistantEvent.getId(), reply.answer());
            if (trace.available()) traceService.finishRootRunSuccess(trace.traceId(), reply.workflowId(), assistantEvent.getId(), Map.of("answer", reply.answer()));

            idempotencyService.markSuccess(idempotencyKey, "sendMessage", assistantEvent.getId());

            return new SendMessageResult(
                    reply.answer(),
                    userEvent.getId(),
                    workflowEvent.getId(),
                    assistantEvent.getId(),
                    reply.workflowId(),
                    null,
                    trace.traceId(),
                    trace.available()
            );
        } catch (Exception ex) {
            if (trace.available()) traceService.finishRootRunFailure(trace.traceId(), "SEND_MESSAGE_FAILED", ex);
            idempotencyService.markFailed(idempotencyKey);
            throw ex;
        }
    }

    private TraceService.RootTrace startTrace(Session session, Long inputEventId, String content, boolean reused) {
        if (traceService == null) return TraceService.RootTrace.unavailable();
        return traceService.startRootRun(session.getId(), session.getUserId(), inputEventId,
                null, null, AgentRunService.PROMPT_VERSION, Map.of("message", content, "idempotencyReused", reused));
    }

    public List<SessionEvent> listEvents(Long sessionId) {
        requireExistingSession(sessionId);
        return eventService.listEvents(sessionId);
    }

    private Session requireExistingSession(Long sessionId) {
        if (sessionId == null) {
            throw new BusinessException(ResultCode.VALIDATION_FAILED, "sessionId is required");
        }
        return repository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "session not found"));
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String metadata(String key, String value) {
        if (value == null || value.isBlank()) {
            return "{}";
        }
        return "{\"" + escape(key) + "\":\"" + escape(value) + "\"}";
    }

    private static String metadata(String... fields) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (int index = 0; index + 1 < fields.length; index += 2) {
            String value = fields[index + 1];
            if (value == null || value.isBlank()) {
                continue;
            }
            if (!first) {
                json.append(",");
            }
            json.append("\"")
                    .append(escape(fields[index]))
                    .append("\":\"")
                    .append(escape(value))
                    .append("\"");
            first = false;
        }
        return json.append("}").toString();
    }

    private static String value(Long value) {
        return value == null ? null : value.toString();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private void recordStage3Audit(
            Long sessionId,
            Long workflowId,
            List<Long> documentIds,
            List<Long> retrievalChunkIds,
            List<Long> memoryIds,
            List<Long> eventIds
    ) {
        auditService.recordKnowledgeRetrieved(sessionId, workflowId, documentIds, retrievalChunkIds);
        auditService.recordMemoryRead(sessionId, workflowId, memoryIds);
        auditService.recordContextAssembled(sessionId, workflowId, documentIds, retrievalChunkIds, memoryIds, eventIds);
    }

    private AgentRun safeStartAgentRun(
            Long sessionId,
            Long workflowId,
            Long taskId,
            String correlationId,
            String runType,
            String source,
            String createdBy
    ) {
        try {
            return agentRunService.startRun(sessionId, workflowId, taskId, correlationId, runType, source, createdBy);
        } catch (RuntimeException ex) {
            log.warn("Failed to start agent run for session {}", sessionId, ex);
            return null;
        }
    }

    private void safeMarkContextReady(
            AgentRun agentRun,
            Long workflowId,
            List<Long> inputEventIds,
            List<Long> contextEventIds,
            List<Long> retrievalChunkIds,
            List<Long> memoryIds
    ) {
        if (agentRun == null) {
            return;
        }
        try {
            travelcare_agent.workflow.entity.Workflow workflow = workflowId == null
                    ? null
                    : workflowRepository.findById(workflowId).orElse(null);
            if (workflow != null) {
                agentRunService.attachWorkflow(agentRun.getId(), workflow.getId());
            }
            List<Long> finalInputEventIds = contextEventIds == null || contextEventIds.isEmpty()
                    ? inputEventIds
                    : contextEventIds;
            agentRunService.markContextReady(
                    agentRun.getId(),
                    finalInputEventIds,
                    retrievalChunkIds,
                    memoryIds,
                    workflowSnapshot(workflow),
                    AgentRunService.PROMPT_VERSION,
                    AgentRunService.RESPONSE_TEMPLATE_VERSION
            );
        } catch (RuntimeException ex) {
            log.warn("Failed to mark agent run context ready: {}", agentRun.getId(), ex);
        }
    }

    private void safeMarkContextFromException(
            AgentRun agentRun,
            List<Long> inputEventIds,
            AgentOrchestrator.AgentStageException exception
    ) {
        if (agentRun == null || exception.agentContext() == null) {
            return;
        }
        List<Long> retrievalChunkIds = exception.agentContext().policySnippets().stream()
                .map(travelcare_agent.retrieval.service.RetrievalSnippet::chunkId)
                .toList();
        List<Long> memoryIds = exception.agentContext().activeMemories().stream()
                .map(travelcare_agent.memory.entity.AgentMemory::getId)
                .toList();
        List<Long> eventIds = exception.agentContext().recentEvents().stream()
                .map(SessionEvent::getId)
                .toList();
        safeMarkContextReady(agentRun, exception.workflowId(), inputEventIds, eventIds, retrievalChunkIds, memoryIds);
    }

    private void safeMarkSucceeded(AgentRun agentRun, Long outputEventId, String answer) {
        if (agentRun == null) {
            return;
        }
        try {
            agentRunService.markSucceeded(agentRun.getId(), outputEventId, answer);
        } catch (RuntimeException ex) {
            log.warn("Failed to mark agent run succeeded: {}", agentRun.getId(), ex);
        }
    }

    private void safeMarkFailed(AgentRun agentRun, String status, String errorCode, RuntimeException error) {
        if (agentRun == null) {
            return;
        }
        try {
            agentRunService.markFailed(agentRun.getId(), status, errorCode, error);
        } catch (RuntimeException ex) {
            log.warn("Failed to mark agent run failed: {}", agentRun.getId(), ex);
        }
    }

    private String workflowSnapshot(travelcare_agent.workflow.entity.Workflow workflow) {
        if (workflow == null) {
            return "{}";
        }
        return AgentRunService.workflowSnapshotJson(
                workflow.getId(),
                workflow.getStatus() == null ? null : workflow.getStatus().name(),
                workflow.getCurrentStep(),
                workflow.getVersion(),
                workflow.getStateJson()
        );
    }

    public record CreateSessionResult(Long sessionId, String status) {
    }

    public record SendMessageResult(
            String answer,
            Long userEventId,
            Long workflowEventId,
            Long assistantEventId,
            Long workflowId,
            Long taskId,
            String traceId,
            boolean traceAvailable
    ) {
    }
}
