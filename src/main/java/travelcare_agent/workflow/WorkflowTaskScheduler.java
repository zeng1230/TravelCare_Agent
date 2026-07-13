package travelcare_agent.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import travelcare_agent.audit.AuditService;
import travelcare_agent.workflow.entity.WorkflowTask;
import travelcare_agent.workflow.repository.WorkflowTaskRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Component
public class WorkflowTaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTaskScheduler.class);

    private final WorkflowTaskRepository taskRepository;
    private final WorkflowTaskService taskService;
    private final AuditService auditService;

    public WorkflowTaskScheduler(
            WorkflowTaskRepository taskRepository,
            WorkflowTaskService taskService,
            AuditService auditService
    ) {
        this.taskRepository = taskRepository;
        this.taskService = taskService;
        this.auditService = auditService;
    }

    @Scheduled(cron = "0 */1 * * * *")
    public void processPendingTasks() {
        log.info("Starting scheduled pending task re-dispatch check");
        LocalDateTime now = LocalDateTime.now();
        List<WorkflowTask> pendingTasks = taskRepository.findDispatchableTasks(now, 100);

        for (WorkflowTask task : pendingTasks) {
            log.info("Processing dispatchable workflow task ID: {}, attempt: {}", task.getId(), task.getAttemptCount());
            try {
                String correlationId = UUID.randomUUID().toString();
                taskService.dispatchTaskIfPending(task.getId(), correlationId, null, null).ifPresent(dispatched -> {
                    auditService.recordTaskDispatch(
                            dispatched.getSessionId(),
                            dispatched.getWorkflowId(),
                            dispatched.getId()
                    );
                    log.info("Successfully dispatched workflow task {}", dispatched.getId());
                });
            } catch (Exception e) {
                auditService.recordTaskFailure(
                        task.getSessionId(),
                        task.getWorkflowId(),
                        task.getId(),
                        "Failed to dispatch workflow task outbox"
                );
                log.error("Failed to dispatch workflow task ID: {}", task.getId(), e);
            }
        }
    }
}
