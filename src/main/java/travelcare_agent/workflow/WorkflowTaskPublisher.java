package travelcare_agent.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import travelcare_agent.audit.AuditService;
import travelcare_agent.config.RabbitMqConfig;
import travelcare_agent.enums.WorkflowTaskStatus;
import travelcare_agent.outbox.OutboxEvent;
import travelcare_agent.outbox.OutboxEventService;
import travelcare_agent.workflow.event.TaskCreatedEvent;

@Component
public class WorkflowTaskPublisher {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTaskPublisher.class);

    private final WorkflowTaskService workflowTaskService;
    private final AuditService auditService;
    private final OutboxEventService outboxEventService;

    public WorkflowTaskPublisher(
            WorkflowTaskService workflowTaskService,
            AuditService auditService,
            OutboxEventService outboxEventService) {
        this.workflowTaskService = workflowTaskService;
        this.auditService = auditService;
        this.outboxEventService = outboxEventService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTaskCreated(TaskCreatedEvent event) {
        travelcare_agent.dryrun.SideEffectGuard.checkCurrent(travelcare_agent.dryrun.SideEffectOperation.RABBITMQ_PUBLISH);
        Long taskId = event.getTaskId();
        Long sessionId = event.getSessionId();
        Long workflowId = event.getWorkflowId();
        String correlationId = event.getCorrelationId();
        
        try {
            String payload = "{\"taskId\":" + taskId
                    + ",\"correlationId\":\"" + safe(correlationId)
                    + "\",\"traceId\":" + jsonString(event.getTraceId())
                    + ",\"parentSpanId\":" + jsonString(event.getParentSpanId())
                    + "}";
            OutboxEvent outboxEvent = outboxEventService.createOrReuse(new OutboxEventService.CreateCommand(
                    "WORKFLOW_TASK_DISPATCH",
                    "workflow_task",
                    String.valueOf(taskId),
                    RabbitMqConfig.ROUTING_KEY_WORKFLOW_TASKS,
                    payload,
                    "workflow_task:" + taskId + ":attempt:0",
                    event.getTraceId(),
                    null));
            workflowTaskService.updateStatus(taskId, WorkflowTaskStatus.DISPATCHED);
            auditService.recordTaskDispatch(sessionId, workflowId, taskId);
            log.info("Workflow task outbox event created taskId={} workflowId={} outboxEventId={} traceId={}",
                    taskId, workflowId, outboxEvent.getId(), event.getTraceId());
        } catch (Exception e) {
            log.error("Failed to create workflow task outbox event taskId={} workflowId={} traceId={}",
                    taskId, workflowId, event.getTraceId(), e);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String jsonString(String value) {
        return value == null ? "null" : "\"" + safe(value) + "\"";
    }
}
