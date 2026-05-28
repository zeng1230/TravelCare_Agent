package travelcare_agent.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import travelcare_agent.audit.AuditService;
import travelcare_agent.enums.WorkflowTaskStatus;
import travelcare_agent.workflow.entity.WorkflowTask;
import travelcare_agent.workflow.event.TaskCreatedEvent;
import travelcare_agent.workflow.repository.WorkflowTaskRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Component
public class WorkflowTaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTaskScheduler.class);

    private final WorkflowTaskRepository taskRepository;
    private final WorkflowTaskPublisher taskPublisher;
    private final AuditService auditService;

    public WorkflowTaskScheduler(
            WorkflowTaskRepository taskRepository,
            WorkflowTaskPublisher taskPublisher,
            AuditService auditService
    ) {
        this.taskRepository = taskRepository;
        this.taskPublisher = taskPublisher;
        this.auditService = auditService;
    }

    @Scheduled(cron = "0 */1 * * * *")
    @Transactional
    public void processPendingTasks() {
        log.info("Starting scheduled pending task re-dispatch check");
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(2);
        List<WorkflowTask> pendingTasks = taskRepository.findPendingTasksCreatedBefore(threshold);

        for (WorkflowTask task : pendingTasks) {
            log.info("Processing orphaned pending task ID: {}, attempt: {}", task.getId(), task.getAttemptCount());
            int newAttemptCount = task.getAttemptCount() + 1;
            task.setAttemptCount(newAttemptCount);

            if (newAttemptCount >= task.getMaxAttempts()) {
                task.setStatus(WorkflowTaskStatus.FAILED);
                taskRepository.save(task);
                auditService.recordTaskFailure(
                        task.getSessionId(),
                        task.getWorkflowId(),
                        task.getId(),
                        "Max attempts (" + task.getMaxAttempts() + ") reached for pending task outbox dispatch"
                );
                log.warn("Workflow task {} failed due to max outbox dispatch attempts", task.getId());
            } else {
                taskRepository.save(task);
                try {
                    String correlationId = UUID.randomUUID().toString();
                    taskPublisher.onTaskCreated(new TaskCreatedEvent(
                            task.getId(),
                            task.getSessionId(),
                            task.getWorkflowId(),
                            correlationId
                    ));
                    log.info("Successfully re-dispatched workflow task {}", task.getId());
                } catch (Exception e) {
                    log.error("Failed to re-dispatch pending task ID: {}", task.getId(), e);
                }
            }
        }
    }
}
