package travelcare_agent.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
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
import travelcare_agent.retrieval.service.RetrievalSnippet;
import travelcare_agent.memory.entity.AgentMemory;
import travelcare_agent.human.service.HumanReviewService;
import travelcare_agent.refund.entity.RefundCase;
import travelcare_agent.refund.repository.RefundCaseRepository;

import java.time.LocalDateTime;
import java.util.Map;

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
            ContextAssembler contextAssembler
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
    }

    @RabbitListener(queues = RabbitMqConfig.QUEUE_WORKFLOW_TASKS)
    public void processTask(Map<String, Object> messagePayload) {
        Long taskId = extractLong(messagePayload.get("taskId"));
        if (taskId == null) {
            log.error("Received message without taskId");
            return;
        }

        WorkflowTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            log.error("Task {} not found", taskId);
            return;
        }

        if (task.getStatus() != WorkflowTaskStatus.PENDING && task.getStatus() != WorkflowTaskStatus.DISPATCHED) {
            log.info("Task {} is in status {}, skipping", taskId, task.getStatus());
            return;
        }

        String lockKey = "workflow:" + task.getWorkflowId() + ":lock";
        
        try {
            lockService.withLock(lockKey, 30000, () -> {
                // Re-fetch inside lock to ensure we have latest state
                WorkflowTask lockedTask = taskRepository.findById(taskId).orElseThrow();
                if (lockedTask.getStatus() != WorkflowTaskStatus.PENDING && lockedTask.getStatus() != WorkflowTaskStatus.DISPATCHED) {
                    return null;
                }
                
                taskService.updateStatus(taskId, WorkflowTaskStatus.RUNNING);
                executeWorkflow(lockedTask);
                return null;
            });
        } catch (IllegalStateException e) {
            log.warn("Lock conflict for task {}: {}", taskId, e.getMessage());
            // Lock conflict, could retry or let it fail
            taskService.incrementRetry(taskId, "LOCK_CONFLICT", "Could not acquire lock", LocalDateTime.now().plusMinutes(1));
        } catch (Exception e) {
            log.error("Error executing task {}", taskId, e);
            taskService.incrementRetry(taskId, "SYSTEM_ERROR", e.getMessage(), LocalDateTime.now().plusMinutes(1));
        }
    }

    private void executeWorkflow(WorkflowTask task) {
        try {
            Session session = sessionRepository.findById(task.getSessionId()).orElseThrow(() -> new BusinessException(travelcare_agent.common.result.ResultCode.NOT_FOUND, "Session not found"));
            
            // Extract message from payload
            Map<String, String> payload = objectMapper.readValue(task.getPayloadJson(), new TypeReference<>() {});
            String content = payload.get("message");
            
            MockIntentClassifier.IntentResult intent = intentClassifier.classify(content);
            
            WorkflowEngine.WorkflowCommand command = new WorkflowEngine.WorkflowCommand(
                    session.getId(),
                    session.getUserId(),
                    null,
                    intent.orderNo(),
                    content
            );

            eventService.appendWorkflowRequested(
                    session.getId(),
                    "{\"workflowType\":\"order_refund_inquiry\",\"workflowId\":\"" + task.getWorkflowId() + "\",\"intent\":\"" + intent.intent() + "\",\"orderNo\":\"" + escape(intent.orderNo()) + "\"}"
            );

            WorkflowEngine.WorkflowResult result = workflowEngine.resume(task.getWorkflowId(), task.getTaskType(), command);

            AgentContext agentContext = contextAssembler.assemble(session.getId(), content);
            String answer = responseGenerator.generate(intent, result, agentContext);

            java.util.Map<String, Object> metaMap = new java.util.HashMap<>();
            metaMap.put("source", "worker");
            metaMap.put("taskId", task.getId());
            metaMap.put("retrievalChunkIds", agentContext.policySnippets().stream().map(RetrievalSnippet::chunkId).toList());
            metaMap.put("memoryIds", agentContext.activeMemories().stream().map(AgentMemory::getId).toList());
            String metaJson = "{}";
            try {
                metaJson = objectMapper.writeValueAsString(metaMap);
            } catch (Exception ignore) {}

            eventService.appendMessage(
                    session.getId(),
                    SessionEventRole.ASSISTANT,
                    answer,
                    metaJson
            );

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

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
