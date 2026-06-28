package travelcare_agent.workflow;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import travelcare_agent.config.RabbitMqConfig;
import travelcare_agent.enums.WorkflowTaskStatus;
import travelcare_agent.outbox.OutboxEvent;
import travelcare_agent.outbox.OutboxEventService;
import travelcare_agent.observability.TravelCareMetrics;
import travelcare_agent.workflow.entity.WorkflowTask;
import travelcare_agent.workflow.event.TaskCreatedEvent;
import travelcare_agent.workflow.repository.WorkflowTaskRepository;

import java.time.LocalDateTime;
import java.util.Objects;

@Service
public class WorkflowTaskService {

    private final WorkflowTaskRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final OutboxEventService outboxEventService;
    private final TravelCareMetrics metrics;

    public WorkflowTaskService(WorkflowTaskRepository repository, ApplicationEventPublisher eventPublisher) {
        this(repository, eventPublisher, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public WorkflowTaskService(
            WorkflowTaskRepository repository,
            ApplicationEventPublisher eventPublisher,
            @org.springframework.beans.factory.annotation.Autowired(required = false) OutboxEventService outboxEventService,
            @org.springframework.beans.factory.annotation.Autowired(required = false) TravelCareMetrics metrics) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.outboxEventService = outboxEventService;
        this.metrics = metrics;
    }

    @Transactional
    public WorkflowTask createTask(Long workflowId, Long sessionId, String taskType, String payloadJson, String correlationId) {
        return createTask(workflowId, sessionId, taskType, payloadJson, correlationId, null, null);
    }

    @Transactional
    public WorkflowTask createTask(Long workflowId, Long sessionId, String taskType, String payloadJson,
            String correlationId, String traceId, String parentSpanId) {
        travelcare_agent.dryrun.SideEffectGuard.checkCurrent(travelcare_agent.dryrun.SideEffectOperation.WORKFLOW_TASK_WRITE);
        WorkflowTask task = new WorkflowTask();
        task.setWorkflowId(workflowId);
        task.setSessionId(sessionId);
        task.setTaskType(taskType);
        task.setStatus(WorkflowTaskStatus.PENDING);
        task.setPayloadJson(payloadJson);
        task.setAttemptCount(0);
        task.setMaxAttempts(3);
        WorkflowTask saved = repository.save(task);
        createDispatchOutbox(saved, traceId, parentSpanId, 0, null);
        eventPublisher.publishEvent(new TaskCreatedEvent(saved.getId(), sessionId, workflowId, correlationId, traceId, parentSpanId));
        return saved;
    }

    public WorkflowTask updateStatus(Long taskId, WorkflowTaskStatus status) {
        travelcare_agent.dryrun.SideEffectGuard.checkCurrent(travelcare_agent.dryrun.SideEffectOperation.WORKFLOW_TASK_WRITE);
        WorkflowTask task = repository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found"));
        task.setStatus(status);
        return repository.save(task);
    }

    public WorkflowTask incrementRetry(Long taskId, String errorCode, String errorMessage, LocalDateTime nextRunAt) {
        travelcare_agent.dryrun.SideEffectGuard.checkCurrent(travelcare_agent.dryrun.SideEffectOperation.WORKFLOW_TASK_WRITE);
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

    @Transactional
    public WorkflowTask recordSkipped(Long taskId, String skippedReason) {
        WorkflowTask task = repository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found"));
        task.setLastSkippedReason(safeCode(skippedReason));
        WorkflowTask saved = repository.save(task);
        if (metrics != null) metrics.workerSkipped(saved.getTaskType(), saved.getLastSkippedReason());
        return saved;
    }

    @Transactional
    public WorkflowTask handleWorkerFailure(Long taskId, String errorCode, String errorMessage,
            LocalDateTime nextRunAt, String traceId) {
        travelcare_agent.dryrun.SideEffectGuard.checkCurrent(travelcare_agent.dryrun.SideEffectOperation.WORKFLOW_TASK_WRITE);
        WorkflowTask task = repository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found"));
        int attempts = nullToZero(task.getAttemptCount()) + 1;
        task.setAttemptCount(attempts);
        task.setLastErrorCode(safeCode(errorCode));
        task.setLastErrorMessage(safeMessage(errorMessage));
        if (attempts >= nullToMax(task.getMaxAttempts())) {
            task.setStatus(WorkflowTaskStatus.FAILED);
            task.setDeadLetterReason("MAX_ATTEMPTS_REACHED");
            WorkflowTask saved = repository.save(task);
            createDeadLetterOutbox(saved, traceId, safeCode(errorCode), attempts, "MAX_ATTEMPTS_REACHED");
            if (metrics != null) metrics.workerDeadLettered(saved.getTaskType(), safeCode(errorCode));
            return saved;
        }
        task.setNextRunAt(nextRunAt);
        WorkflowTask saved = repository.save(task);
        createDispatchOutbox(saved, traceId, null, attempts, nextRunAt);
        if (metrics != null) metrics.workerRetryScheduled(saved.getTaskType(), safeCode(errorCode));
        return saved;
    }

    public WorkflowTask markTerminalState(Long taskId, WorkflowTaskStatus terminalStatus) {
        travelcare_agent.dryrun.SideEffectGuard.checkCurrent(travelcare_agent.dryrun.SideEffectOperation.WORKFLOW_TASK_WRITE);
        if (terminalStatus != WorkflowTaskStatus.SUCCEEDED && 
            terminalStatus != WorkflowTaskStatus.FAILED && 
            terminalStatus != WorkflowTaskStatus.NEED_HUMAN &&
            terminalStatus != WorkflowTaskStatus.CANCELLED) {
            throw new IllegalArgumentException("Not a terminal state");
        }
        WorkflowTask task = repository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found"));
        task.setStatus(terminalStatus);
        WorkflowTask saved = repository.save(task);
        if (metrics != null) {
            if (terminalStatus == WorkflowTaskStatus.SUCCEEDED) {
                metrics.workerSucceeded(saved.getTaskType());
            } else if (terminalStatus == WorkflowTaskStatus.FAILED) {
                metrics.workerFailed(saved.getTaskType(), safeCode(saved.getLastErrorCode()));
            } else if (terminalStatus == WorkflowTaskStatus.NEED_HUMAN) {
                metrics.workerSkipped(saved.getTaskType(), "NEED_HUMAN");
            }
        }
        return saved;
    }

    private void createDispatchOutbox(WorkflowTask task, String traceId, String parentSpanId, int attempts,
            LocalDateTime nextRetryAt) {
        if (outboxEventService == null) {
            return;
        }
        String payload = "{\"taskId\":" + task.getId()
                + ",\"traceId\":" + jsonString(traceId)
                + ",\"parentSpanId\":" + jsonString(parentSpanId)
                + "}";
        OutboxEvent event = outboxEventService.createOrReuse(new OutboxEventService.CreateCommand(
                "WORKFLOW_TASK_DISPATCH",
                "workflow_task",
                String.valueOf(task.getId()),
                RabbitMqConfig.ROUTING_KEY_WORKFLOW_TASKS,
                payload,
                "workflow_task:" + task.getId() + ":attempt:" + attempts,
                traceId,
                nextRetryAt));
        task.setLastOutboxEventId(event.getId());
        repository.save(task);
    }

    private void createDeadLetterOutbox(WorkflowTask task, String traceId, String failureCode, int attempts,
            String reason) {
        if (outboxEventService == null) {
            return;
        }
        String payload = "{\"taskId\":" + task.getId()
                + ",\"workflowId\":" + task.getWorkflowId()
                + ",\"toolCallId\":null"
                + ",\"traceId\":" + jsonString(traceId)
                + ",\"failureCode\":" + jsonString(failureCode)
                + ",\"attempts\":" + attempts
                + ",\"deadLetterReason\":" + jsonString(reason)
                + ",\"outboxEventId\":null"
                + ",\"createdAt\":" + jsonString(LocalDateTime.now().toString())
                + "}";
        OutboxEvent event = outboxEventService.createOrReuse(new OutboxEventService.CreateCommand(
                "WORKFLOW_TASK_DEAD_LETTER",
                "workflow_task",
                String.valueOf(task.getId()),
                RabbitMqConfig.ROUTING_KEY_WORKFLOW_TASKS_DLQ,
                payload,
                "workflow_task:" + task.getId() + ":dead-letter:" + attempts,
                traceId,
                null));
        task.setLastOutboxEventId(event.getId());
        repository.save(task);
    }

    private static int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static int nullToMax(Integer value) {
        return value == null ? 3 : value;
    }

    private static String safeCode(String value) {
        if (value == null) return null;
        return value.matches("[A-Z0-9_\\-]{1,128}") ? value : "SYSTEM_ERROR";
    }

    private static String safeMessage(String value) {
        if (value == null) return null;
        return value.length() > 512 ? value.substring(0, 512) : value;
    }

    private static String jsonString(String value) {
        if (Objects.isNull(value)) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
