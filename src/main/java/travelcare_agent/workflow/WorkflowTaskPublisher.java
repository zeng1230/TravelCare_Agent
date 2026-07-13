package travelcare_agent.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import travelcare_agent.audit.AuditService;
import travelcare_agent.workflow.event.TaskCreatedEvent;

@Component
public class WorkflowTaskPublisher {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTaskPublisher.class);

    private final WorkflowTaskService workflowTaskService;
    private final AuditService auditService;

    public WorkflowTaskPublisher(
            WorkflowTaskService workflowTaskService,
            AuditService auditService) {
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
            workflowTaskService.dispatchTaskIfPending(taskId, correlationId, event.getTraceId(), event.getParentSpanId())
                    .ifPresent(dispatched -> {
                        auditService.recordTaskDispatch(sessionId, workflowId, taskId);
                        log.info("Workflow task dispatch claimed taskId={} workflowId={} traceId={}",
                                taskId, workflowId, event.getTraceId());
                    });
        } catch (Exception e) {
            log.error("Failed to create workflow task outbox event taskId={} workflowId={} traceId={}",
                    taskId, workflowId, event.getTraceId(), e);
        }
    }
}
