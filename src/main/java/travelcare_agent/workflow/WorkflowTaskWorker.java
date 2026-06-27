package travelcare_agent.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import travelcare_agent.agentrun.entity.AgentRun;
import travelcare_agent.agentrun.service.AgentRunService;
import travelcare_agent.agent.MockIntentClassifier;
import travelcare_agent.agent.MockResponseGenerator;
import travelcare_agent.audit.AuditService;
import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.common.lock.LockService;
import travelcare_agent.config.RabbitMqConfig;
import travelcare_agent.conversation.entity.Session;
import travelcare_agent.conversation.repository.SessionRepository;
import travelcare_agent.conversation.service.SessionEventService;
import travelcare_agent.enums.SessionEventRole;
import travelcare_agent.enums.WorkflowTaskStatus;
import travelcare_agent.workflow.entity.WorkflowTask;
import travelcare_agent.workflow.repository.WorkflowTaskRepository;

import travelcare_agent.agent.ContextAssembler;
import travelcare_agent.agent.AgentContext;
import travelcare_agent.agent.AgentModelService;
import travelcare_agent.retrieval.service.RetrievalSnippet;
import travelcare_agent.memory.entity.AgentMemory;
import travelcare_agent.human.service.HumanReviewService;
import travelcare_agent.refund.entity.RefundCase;
import travelcare_agent.refund.repository.RefundCaseRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import travelcare_agent.trace.*;

@Component
public class WorkflowTaskWorker {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTaskWorker.class);

    private final WorkflowTaskRepository taskRepository;
    private final WorkflowTaskService taskService;
    private final LockService lockService;
    private final WorkflowEngine workflowEngine;
    private final SessionRepository sessionRepository;
    private final SessionEventService eventService;
    private final MockIntentClassifier intentClassifier;
    private final MockResponseGenerator responseGenerator;
    private final ObjectMapper objectMapper;
    // Note: auditService is required if there are worker-specific audits, 
    // but workflowEngine already audits rule checks and queries.
    private final AuditService auditService;
    private final HumanReviewService humanReviewService;
    private final RefundCaseRepository refundCaseRepository;
    private final ContextAssembler contextAssembler;
    private final AgentRunService agentRunService;
    private final AgentModelService agentModelService;
    private final TraceService traceService;

    @Autowired
    public WorkflowTaskWorker(
            WorkflowTaskRepository taskRepository,
            WorkflowTaskService taskService,
            LockService lockService,
            WorkflowEngine workflowEngine,
            SessionRepository sessionRepository,
            SessionEventService eventService,
            MockIntentClassifier intentClassifier,
            MockResponseGenerator responseGenerator,
            ObjectMapper objectMapper,
            AuditService auditService,
            HumanReviewService humanReviewService,
            RefundCaseRepository refundCaseRepository,
            ContextAssembler contextAssembler,
            AgentRunService agentRunService,
            AgentModelService agentModelService,
            TraceService traceService
    ) {
        this.taskRepository = taskRepository;
        this.taskService = taskService;
        this.lockService = lockService;
        this.workflowEngine = workflowEngine;
        this.sessionRepository = sessionRepository;
        this.eventService = eventService;
        this.intentClassifier = intentClassifier;
        this.responseGenerator = responseGenerator;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
        this.humanReviewService = humanReviewService;
        this.refundCaseRepository = refundCaseRepository;
        this.contextAssembler = contextAssembler;
        this.agentRunService = agentRunService;
        this.agentModelService = agentModelService;
        this.traceService = traceService;
    }

    public WorkflowTaskWorker(WorkflowTaskRepository taskRepository, WorkflowTaskService taskService,
            LockService lockService, WorkflowEngine workflowEngine, SessionRepository sessionRepository,
            SessionEventService eventService, MockIntentClassifier intentClassifier,
            MockResponseGenerator responseGenerator, ObjectMapper objectMapper, AuditService auditService,
            HumanReviewService humanReviewService, RefundCaseRepository refundCaseRepository,
            ContextAssembler contextAssembler, AgentRunService agentRunService, AgentModelService agentModelService) {
        this(taskRepository, taskService, lockService, workflowEngine, sessionRepository, eventService,
                intentClassifier, responseGenerator, objectMapper, auditService, humanReviewService,
                refundCaseRepository, contextAssembler, agentRunService, agentModelService, null);
    }

    public WorkflowTaskWorker(
            WorkflowTaskRepository taskRepository,
            WorkflowTaskService taskService,
            LockService lockService,
            WorkflowEngine workflowEngine,
            SessionRepository sessionRepository,
            SessionEventService eventService,
            MockIntentClassifier intentClassifier,
            MockResponseGenerator responseGenerator,
            ObjectMapper objectMapper,
            AuditService auditService,
            HumanReviewService humanReviewService,
            RefundCaseRepository refundCaseRepository,
            ContextAssembler contextAssembler,
            AgentRunService agentRunService
    ) {
        this(taskRepository, taskService, lockService, workflowEngine, sessionRepository, eventService,
                intentClassifier, responseGenerator, objectMapper, auditService, humanReviewService,
                refundCaseRepository, contextAssembler, agentRunService, null, null);
    }

    @RabbitListener(queues = RabbitMqConfig.QUEUE_WORKFLOW_TASKS)
    public void processTask(Object rawPayload) {
        Map<String, Object> messagePayload = parseMessagePayload(rawPayload);
        Long taskId = extractLong(messagePayload.get("taskId"));
        if (taskId == null) {
            log.error("Received message without taskId");
            throw new AmqpRejectAndDontRequeueException("workflow task message missing taskId");
        }

        WorkflowTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            log.error("Task {} not found", taskId);
            throw new AmqpRejectAndDontRequeueException("workflow task not found: " + taskId);
        }

        String traceId = stringValue(messagePayload.get("traceId"));
        String parentSpanId = stringValue(messagePayload.get("parentSpanId"));
        TraceService.SpanHandle asyncSpan = traceService == null ? TraceService.SpanHandle.unavailable()
                : traceService.startSpan(traceId, parentSpanId, SpanType.ASYNC_TASK, "workflow-task", Map.of("taskId", taskId));

        try (TraceContextHolder.Scope ignored = asyncSpan.available()
                ? TraceContextHolder.attach(asyncSpan.traceId(), asyncSpan.spanId()) : null) {
        if (task.getStatus() != WorkflowTaskStatus.PENDING && task.getStatus() != WorkflowTaskStatus.DISPATCHED) {
            log.info("Task {} is in status {}, skipping", taskId, task.getStatus());
            taskService.recordSkipped(taskId, "TERMINAL_OR_NON_RUNNABLE_STATUS");
            return;
        }

        String lockKey = "workflow:" + task.getWorkflowId() + ":lock";
        
        try {
            lockService.withLock(lockKey, 30000, () -> {
                // Re-fetch inside lock to ensure we have latest state
                WorkflowTask lockedTask = taskRepository.findById(taskId).orElseThrow();
                if (lockedTask.getStatus() != WorkflowTaskStatus.PENDING && lockedTask.getStatus() != WorkflowTaskStatus.DISPATCHED) {
                    taskService.recordSkipped(taskId, "STALE_AFTER_LOCK");
                    return null;
                }
                
                taskService.updateStatus(taskId, WorkflowTaskStatus.RUNNING);
                executeWorkflow(lockedTask);
                return null;
            });
        } catch (IllegalStateException e) {
            log.warn("Lock conflict for task {}: {}", taskId, e.getMessage());
            taskService.handleWorkerFailure(taskId, "LOCK_CONFLICT", "Could not acquire lock",
                    LocalDateTime.now().plusMinutes(1), traceId);
        } catch (Exception e) {
            log.error("Error executing task {}", taskId, e);
            taskService.handleWorkerFailure(taskId, "SYSTEM_ERROR", e.getMessage(),
                    LocalDateTime.now().plusMinutes(1), traceId);
        }
        } finally {
            if (traceService != null) traceService.finishSpanSuccess(asyncSpan, "WORKFLOW_TASK:" + taskId, Map.of());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMessagePayload(Object rawPayload) {
        if (rawPayload instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (rawPayload instanceof String text) {
            try {
                return objectMapper.readValue(text, new TypeReference<>() {});
            } catch (Exception ex) {
                throw new AmqpRejectAndDontRequeueException("invalid workflow task payload", ex);
            }
        }
        throw new AmqpRejectAndDontRequeueException("unsupported workflow task payload type");
    }

    private void executeWorkflow(WorkflowTask task) {
        AgentRun agentRun = null;
        try {
            Session session = sessionRepository.findById(task.getSessionId()).orElseThrow(() -> new BusinessException(travelcare_agent.common.result.ResultCode.NOT_FOUND, "Session not found"));
            
            // Extract message from payload
            Map<String, String> payload = objectMapper.readValue(task.getPayloadJson(), new TypeReference<>() {});
            String content = payload.get("message");
            Long userEventId = extractLong(payload.get("userEventId"));
            agentRun = safeStartAgentRun(
                    task.getSessionId(),
                    task.getWorkflowId(),
                    task.getId(),
                    null,
                    "ASYNC_WORKER_REPLY",
                    "workflow_task_worker",
                    "WORKER"
            );
            
            List<Long> modelInputEventIds = userEventId == null ? List.of() : List.of(userEventId);
            MockIntentClassifier.IntentResult intent = agentModelService == null
                    ? intentClassifier.classify(content)
                    : agentModelService.classifyIntentAndExtractSlots(
                            task.getSessionId(), task.getWorkflowId(), modelInputEventIds, List.of(), content
                    );
            
            WorkflowEngine.WorkflowCommand command = new WorkflowEngine.WorkflowCommand(
                    session.getId(),
                    session.getUserId(),
                    null,
                    intent.orderNo(),
                    content
            );

            try {
                eventService.appendWorkflowRequested(
                        session.getId(),
                        "{\"workflowType\":\"order_refund_inquiry\",\"workflowId\":\"" + task.getWorkflowId() + "\",\"intent\":\"" + intent.intent() + "\",\"orderNo\":\"" + escape(intent.orderNo()) + "\"}"
                );
            } catch (RuntimeException ex) {
                safeMarkFailed(agentRun, "FAILED_OUTPUT_EVENT", "WORKFLOW_EVENT_WRITE_FAILED", ex);
                throw ex;
            }

            WorkflowEngine.WorkflowResult result;
            try {
                result = workflowEngine.resume(task.getWorkflowId(), task.getTaskType(), command);
            } catch (RuntimeException ex) {
                safeMarkFailed(agentRun, "FAILED_GENERATION", "WORKFLOW_RESUME_FAILED", ex);
                throw ex;
            }

            AgentContext agentContext;
            try {
                agentContext = contextAssembler.assemble(session.getId(), content);
            } catch (RuntimeException ex) {
                safeMarkFailed(agentRun, "FAILED_CONTEXT", "CONTEXT_ASSEMBLY_FAILED", ex);
                throw ex;
            }
            safeMarkContextReady(agentRun, result, userEventId, agentContext);
            recordStage3Audit(session.getId(), task.getWorkflowId(), agentContext);
            String answer;
            try {
                String deterministicAnswer = responseGenerator.generate(intent, result, agentContext);
                List<Long> retrievalContextIds = agentContext.policySnippets().stream()
                        .map(travelcare_agent.retrieval.service.RetrievalSnippet::chunkId)
                        .toList();
                answer = agentModelService == null
                        ? deterministicAnswer
                        : agentModelService.generateCustomerAnswer(
                                task.getSessionId(), task.getWorkflowId(), modelInputEventIds,
                                retrievalContextIds, deterministicAnswer
                        );
            } catch (RuntimeException ex) {
                safeMarkFailed(agentRun, "FAILED_GENERATION", "RESPONSE_GENERATION_FAILED", ex);
                throw ex;
            }

            java.util.Map<String, Object> metaMap = new java.util.HashMap<>();
            metaMap.put("source", "worker");
            metaMap.put("taskId", task.getId());
            metaMap.put("retrievalChunkIds", agentContext.policySnippets().stream().map(RetrievalSnippet::chunkId).toList());
            metaMap.put("memoryIds", agentContext.activeMemories().stream().map(AgentMemory::getId).toList());
            String metaJson = "{}";
            try {
                metaJson = objectMapper.writeValueAsString(metaMap);
            } catch (Exception ignore) {}

            travelcare_agent.conversation.entity.SessionEvent assistantEvent;
            TraceService.SpanHandle outputSpan = traceService == null ? TraceService.SpanHandle.unavailable()
                    : traceService.startSpan(SpanType.OUTPUT, "assistant-response", Map.of("taskId", task.getId()));
            try {
                assistantEvent = eventService.appendMessage(
                        session.getId(),
                        SessionEventRole.ASSISTANT,
                        answer,
                        metaJson
                );
                if (traceService != null) traceService.finishSpanSuccess(outputSpan,
                        "SESSION_EVENT:" + assistantEvent.getId(), Map.of("answer", answer));
            } catch (RuntimeException ex) {
                if (traceService != null) traceService.finishSpanFailure(outputSpan,
                        "ASSISTANT_EVENT_WRITE_FAILED", ex, Map.of());
                safeMarkFailed(agentRun, "FAILED_OUTPUT_EVENT", "ASSISTANT_EVENT_WRITE_FAILED", ex);
                throw ex;
            }
            safeMarkSucceeded(agentRun, assistantEvent.getId(), answer);
            TraceContextHolder.TraceContext trace = TraceContextHolder.current();
            if (traceService != null && trace != null) traceService.finishRootRunSuccess(
                    trace.traceId(), task.getWorkflowId(), assistantEvent.getId(), Map.of("answer", answer));

            if (result.workflow().getStatus() == travelcare_agent.enums.WorkflowStatus.NEED_HUMAN) {
                taskService.markTerminalState(task.getId(), WorkflowTaskStatus.NEED_HUMAN);
                
                // Try to resolve refundCaseId
                Long refundCaseId = refundCaseRepository.findByWorkflowId(task.getWorkflowId())
                        .map(RefundCase::getId).orElse(null);
                
                // Extract reasonCode from workflow state if present
                String reasonCode = "NEED_HUMAN";
                try {
                    Map<String, String> state = objectMapper.readValue(result.workflow().getStateJson(), new TypeReference<>() {});
                    if (state != null && state.containsKey("reasonCode")) {
                        reasonCode = state.get("reasonCode");
                    }
                } catch (Exception ignore) {}
                
                humanReviewService.createCase(
                        task.getSessionId(),
                        task.getWorkflowId(),
                        refundCaseId,
                        "REFUND_REVIEW",
                        "HIGH",
                        reasonCode,
                        result.workflow().getStateJson()
                );
            } else if (result.workflow().getStatus() == travelcare_agent.enums.WorkflowStatus.FAILED) {
                taskService.markTerminalState(task.getId(), WorkflowTaskStatus.FAILED);
            } else {
                taskService.markTerminalState(task.getId(), WorkflowTaskStatus.SUCCEEDED);
            }

        } catch (BusinessException be) {
            safeMarkFailed(agentRun, "FAILED_GENERATION", "BUSINESS_ERROR", be);
            taskService.markTerminalState(task.getId(), WorkflowTaskStatus.FAILED);
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Long extractLong(Object obj) {
        if (obj instanceof Number num) {
            return num.longValue();
        } else if (obj instanceof String str) {
            return Long.parseLong(str);
        }
        return null;
    }

    private String stringValue(Object value) { return value == null ? null : String.valueOf(value); }

    private void recordStage3Audit(Long sessionId, Long workflowId, AgentContext agentContext) {
        List<Long> documentIds = agentContext.policySnippets().stream()
                .map(RetrievalSnippet::documentId)
                .distinct()
                .toList();
        List<Long> chunkIds = agentContext.policySnippets().stream()
                .map(RetrievalSnippet::chunkId)
                .toList();
        List<Long> memoryIds = agentContext.activeMemories().stream()
                .map(AgentMemory::getId)
                .toList();
        List<Long> eventIds = agentContext.recentEvents().stream()
                .map(travelcare_agent.conversation.entity.SessionEvent::getId)
                .toList();

        auditService.recordKnowledgeRetrieved(sessionId, workflowId, documentIds, chunkIds);
        auditService.recordMemoryRead(sessionId, workflowId, memoryIds);
        auditService.recordContextAssembled(sessionId, workflowId, documentIds, chunkIds, memoryIds, eventIds);
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
            log.warn("Failed to start agent run for workflow task {}", taskId, ex);
            return null;
        }
    }

    private void safeMarkContextReady(
            AgentRun agentRun,
            WorkflowEngine.WorkflowResult result,
            Long userEventId,
            AgentContext agentContext
    ) {
        if (agentRun == null) {
            return;
        }
        try {
            List<Long> inputEventIds = userEventId == null
                    ? agentContext.recentEvents().stream().map(travelcare_agent.conversation.entity.SessionEvent::getId).toList()
                    : List.of(userEventId);
            List<Long> chunkIds = agentContext.policySnippets().stream()
                    .map(RetrievalSnippet::chunkId)
                    .toList();
            List<Long> memoryIds = agentContext.activeMemories().stream()
                    .map(AgentMemory::getId)
                    .toList();
            agentRunService.markContextReady(
                    agentRun.getId(),
                    inputEventIds,
                    chunkIds,
                    memoryIds,
                    AgentRunService.workflowSnapshotJson(
                            result.workflow().getId(),
                            result.workflow().getStatus() == null ? null : result.workflow().getStatus().name(),
                            result.workflow().getCurrentStep(),
                            result.workflow().getVersion(),
                            result.workflow().getStateJson()
                    ),
                    AgentRunService.PROMPT_VERSION,
                    AgentRunService.RESPONSE_TEMPLATE_VERSION
            );
        } catch (RuntimeException ex) {
            log.warn("Failed to mark agent run context ready: {}", agentRun.getId(), ex);
        }
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

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
