package travelcare_agent.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import travelcare_agent.audit.AuditService;
import travelcare_agent.config.RabbitMqConfig;
import travelcare_agent.enums.WorkflowTaskStatus;
import travelcare_agent.workflow.event.TaskCreatedEvent;

import java.util.HashMap;
import java.util.Map;

@Component
public class WorkflowTaskPublisher {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTaskPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final WorkflowTaskService workflowTaskService;
    private final AuditService auditService;

    public WorkflowTaskPublisher(RabbitTemplate rabbitTemplate, WorkflowTaskService workflowTaskService, AuditService auditService) {
        this.rabbitTemplate = rabbitTemplate;
        this.workflowTaskService = workflowTaskService;
        this.auditService = auditService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTaskCreated(TaskCreatedEvent event) {
        travelcare_agent.dryrun.SideEffectGuard.checkCurrent(travelcare_agent.dryrun.SideEffectOperation.RABBITMQ_PUBLISH);
        Long taskId = event.getTaskId();
        Long sessionId = event.getSessionId();
        Long workflowId = event.getWorkflowId();
        String correlationId = event.getCorrelationId();
        
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("taskId", taskId);
            payload.put("correlationId", correlationId);
            payload.put("traceId", event.getTraceId());
            payload.put("parentSpanId", event.getParentSpanId());

            rabbitTemplate.convertAndSend(
                    RabbitMqConfig.EXCHANGE_WORKFLOW,
                    RabbitMqConfig.ROUTING_KEY_WORKFLOW_TASKS,
                    payload
            );

            workflowTaskService.updateStatus(taskId, WorkflowTaskStatus.DISPATCHED);
            auditService.recordTaskDispatch(sessionId, workflowId, taskId);
        } catch (Exception e) {
            log.error("Failed to publish task to RabbitMQ for taskId: " + taskId, e);
            // On publish failure, keep task PENDING or mark retryable failure.
            // It remains PENDING because we didn't update to DISPATCHED.
        }
    }
}
