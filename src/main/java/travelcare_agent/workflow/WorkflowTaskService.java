package travelcare_agent.workflow;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import travelcare_agent.config.RabbitMqConfig;
import travelcare_agent.enums.WorkflowTaskStatus;
import travelcare_agent.observability.TravelCareMetrics;
import travelcare_agent.outbox.OutboxEvent;
import travelcare_agent.outbox.OutboxEventService;
import travelcare_agent.workflow.entity.WorkflowTask;
import travelcare_agent.workflow.event.TaskCreatedEvent;
import travelcare_agent.workflow.repository.WorkflowTaskRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class WorkflowTaskService {

    private static final List<WorkflowTaskStatus> ACTIVE_STATUSES = List.of(
            WorkflowTaskStatus.PENDING,
            WorkflowTaskStatus.DISPATCHED,
            WorkflowTaskStatus.RUNNING
    );
    private static final List<WorkflowTaskStatus> METADATA_STATUSES = List.of(WorkflowTaskStatus.values());

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
        WorkflowTask saved = repository.insert(task);
        eventPublisher.publishEvent(new TaskCreatedEvent(saved.getId(), sessionId, workflowId,
                correlationId, traceId, parentSpanId));
        return saved;
    }

    @Transactional
    public Optional<WorkflowTask> dispatchTaskIfPending(Long taskId, String correlationId, String traceId,
            String parentSpanId) {
        travelcare_agent.dryrun.SideEffectGuard.checkCurrent(travelcare_agent.dryrun.SideEffectOperation.WORKFLOW_TASK_WRITE);
        WorkflowTask task = repository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found"));
        if (repository.claimForDispatch(taskId, LocalDateTime.now()) != 1) {
            return Optional.empty();
        }
        WorkflowTask claimed = repository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found"));
        OutboxEvent event = createDispatchOutbox(claimed, correlationId, traceId, parentSpanId, null);
        updateLastOutboxEventId(claimed, event.getId(), List.of(WorkflowTaskStatus.DISPATCHED));
        return repository.findById(taskId);
    }

    public WorkflowTask updateStatus(Long taskId, WorkflowTaskStatus status) {
        if (status != WorkflowTaskStatus.DISPATCHED) {
            throw new IllegalArgumentException("Unsupported WorkflowTask status update: " + status);
        }
        return dispatchTaskIfPending(taskId, null, null, null)
                .orElseThrow(() -> conflict(taskId, "Workflow task dispatch claim failed"));
    }

    public WorkflowTask incrementRetry(Long taskId, String errorCode, String errorMessage, LocalDateTime nextRunAt) {
        return handleWorkerFailure(taskId, errorCode, errorMessage, nextRunAt, null);
    }

    @Transactional
    public WorkflowTask recordSkipped(Long taskId, String skippedReason) {
        WorkflowTask task = repository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found"));
        String safeReason = safeCode(skippedReason);
        int rows = repository.recordSkippedReasonIfCurrent(taskId, task.getLastSkippedReason(), safeReason,
                METADATA_STATUSES, LocalDateTime.now());
        WorkflowTask current = repository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found"));
        if (rows == 1 && metrics != null) metrics.workerSkipped(current.getTaskType(), safeReason);
        return current;
    }

    @Transactional
    public WorkflowTask handleWorkerFailure(Long taskId, String errorCode, String errorMessage,
            LocalDateTime nextRunAt, String traceId) {
        travelcare_agent.dryrun.SideEffectGuard.checkCurrent(travelcare_agent.dryrun.SideEffectOperation.WORKFLOW_TASK_WRITE);
        WorkflowTask task = repository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found"));
        int expectedAttempt = nullToZero(task.getAttemptCount());
        int nextAttempt = expectedAttempt + 1;
        String safeCode = safeCode(errorCode);
        String safeMessage = safeMessage(errorMessage);
        if (nextAttempt >= nullToMax(task.getMaxAttempts())) {
            int rows = repository.failIfCurrent(taskId, expectedAttempt, safeCode, safeMessage,
                    "MAX_ATTEMPTS_REACHED", LocalDateTime.now());
            if (rows != 1) throw conflict(taskId, "Workflow task failure update conflicted");
            WorkflowTask saved = repository.findById(taskId).orElseThrow();
            OutboxEvent event = createDeadLetterOutbox(saved, traceId, safeCode, nextAttempt, "MAX_ATTEMPTS_REACHED");
            updateLastOutboxEventId(saved, event.getId(), List.of(WorkflowTaskStatus.FAILED));
            WorkflowTask current = repository.findById(taskId).orElseThrow();
            if (metrics != null) metrics.workerDeadLettered(current.getTaskType(), safeCode);
            return current;
        }
        int rows = repository.retryIfCurrent(taskId, expectedAttempt, safeCode, safeMessage,
                nextRunAt, LocalDateTime.now());
        if (rows != 1) throw conflict(taskId, "Workflow task retry update conflicted");
        WorkflowTask current = repository.findById(taskId).orElseThrow();
        if (metrics != null) metrics.workerRetryScheduled(current.getTaskType(), safeCode);
        return current;
    }

    public WorkflowTask markTerminalState(Long taskId, WorkflowTaskStatus terminalStatus) {
        WorkflowTask task = repository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found"));
        return markTerminalState(taskId, terminalStatus, nullToZero(task.getAttemptCount()));
    }

    @Transactional
    public WorkflowTask markTerminalState(Long taskId, WorkflowTaskStatus terminalStatus, int expectedAttemptCount) {
        travelcare_agent.dryrun.SideEffectGuard.checkCurrent(travelcare_agent.dryrun.SideEffectOperation.WORKFLOW_TASK_WRITE);
        if (!isTerminal(terminalStatus)) {
            throw new IllegalArgumentException("Not a terminal state");
        }
        int rows = repository.markTerminalIfCurrentIn(taskId, terminalStatus, ACTIVE_STATUSES,
                expectedAttemptCount, LocalDateTime.now());
        if (rows != 1) throw conflict(taskId, "Workflow task terminal update conflicted");
        WorkflowTask saved = repository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found"));
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

    private OutboxEvent createDispatchOutbox(WorkflowTask task, String correlationId, String traceId,
            String parentSpanId, LocalDateTime nextRetryAt) {
        if (outboxEventService == null) {
            throw new IllegalStateException("OutboxEventService is required for workflow task dispatch");
        }
        String payload = "{\"taskId\":" + task.getId()
                + ",\"correlationId\":" + jsonString(correlationId)
                + ",\"traceId\":" + jsonString(traceId)
                + ",\"parentSpanId\":" + jsonString(parentSpanId)
                + "}";
        return outboxEventService.createOrReuse(new OutboxEventService.CreateCommand(
                "WORKFLOW_TASK_DISPATCH",
                "workflow_task",
                String.valueOf(task.getId()),
                RabbitMqConfig.ROUTING_KEY_WORKFLOW_TASKS,
                payload,
                "workflow_task:" + task.getId() + ":attempt:" + nullToZero(task.getAttemptCount()),
                traceId,
                nextRetryAt));
    }

    private OutboxEvent createDeadLetterOutbox(WorkflowTask task, String traceId, String failureCode, int attempts,
            String reason) {
        if (outboxEventService == null) {
            throw new IllegalStateException("OutboxEventService is required for workflow task dead letter");
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
        return outboxEventService.createOrReuse(new OutboxEventService.CreateCommand(
                "WORKFLOW_TASK_DEAD_LETTER",
                "workflow_task",
                String.valueOf(task.getId()),
                RabbitMqConfig.ROUTING_KEY_WORKFLOW_TASKS_DLQ,
                payload,
                "workflow_task:" + task.getId() + ":dead-letter:" + attempts,
                traceId,
                null));
    }

    private void updateLastOutboxEventId(WorkflowTask task, Long outboxEventId,
            List<WorkflowTaskStatus> allowedStatuses) {
        int rows = repository.updateLastOutboxEventIdIfCurrent(task.getId(), task.getLastOutboxEventId(),
                outboxEventId, allowedStatuses, LocalDateTime.now());
        if (rows != 1) throw conflict(task.getId(), "Workflow task outbox metadata update conflicted");
    }

    private static boolean isTerminal(WorkflowTaskStatus status) {
        return status == WorkflowTaskStatus.SUCCEEDED
                || status == WorkflowTaskStatus.FAILED
                || status == WorkflowTaskStatus.NEED_HUMAN
                || status == WorkflowTaskStatus.CANCELLED;
    }

    private static WorkflowTaskStateConflictException conflict(Long taskId, String message) {
        return new WorkflowTaskStateConflictException(taskId, message);
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
