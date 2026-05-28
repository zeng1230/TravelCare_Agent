package travelcare_agent.workflow;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import travelcare_agent.enums.WorkflowTaskStatus;
import travelcare_agent.workflow.entity.WorkflowTask;
import travelcare_agent.workflow.event.TaskCreatedEvent;
import travelcare_agent.workflow.repository.WorkflowTaskRepository;

import java.time.LocalDateTime;

@Service
public class WorkflowTaskService {

    private final WorkflowTaskRepository repository;
    private final ApplicationEventPublisher eventPublisher;

    public WorkflowTaskService(WorkflowTaskRepository repository, ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public WorkflowTask createTask(Long workflowId, Long sessionId, String taskType, String payloadJson, String correlationId) {
        WorkflowTask task = new WorkflowTask();
        task.setWorkflowId(workflowId);
        task.setSessionId(sessionId);
        task.setTaskType(taskType);
        task.setStatus(WorkflowTaskStatus.PENDING);
        task.setPayloadJson(payloadJson);
        task.setAttemptCount(0);
        task.setMaxAttempts(3);
        WorkflowTask saved = repository.save(task);
        eventPublisher.publishEvent(new TaskCreatedEvent(saved.getId(), sessionId, workflowId, correlationId));
        return saved;
    }

    public WorkflowTask updateStatus(Long taskId, WorkflowTaskStatus status) {
        WorkflowTask task = repository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found"));
        task.setStatus(status);
        return repository.save(task);
    }

    public WorkflowTask incrementRetry(Long taskId, String errorCode, String errorMessage, LocalDateTime nextRunAt) {
        WorkflowTask task = repository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found"));
        task.setAttemptCount(task.getAttemptCount() + 1);
        task.setLastErrorCode(errorCode);
        task.setLastErrorMessage(errorMessage);
        if (task.getAttemptCount() >= task.getMaxAttempts()) {
            task.setStatus(WorkflowTaskStatus.FAILED);
        } else {
            task.setNextRunAt(nextRunAt);
        }
        return repository.save(task);
    }

    public WorkflowTask markTerminalState(Long taskId, WorkflowTaskStatus terminalStatus) {
        if (terminalStatus != WorkflowTaskStatus.SUCCEEDED && 
            terminalStatus != WorkflowTaskStatus.FAILED && 
            terminalStatus != WorkflowTaskStatus.NEED_HUMAN &&
            terminalStatus != WorkflowTaskStatus.CANCELLED) {
            throw new IllegalArgumentException("Not a terminal state");
        }
        WorkflowTask task = repository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found"));
        task.setStatus(terminalStatus);
        return repository.save(task);
    }
}
