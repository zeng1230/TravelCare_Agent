package travelcare_agent.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import travelcare_agent.enums.WorkflowTaskStatus;
import travelcare_agent.outbox.OutboxEvent;
import travelcare_agent.outbox.OutboxEventService;
import travelcare_agent.workflow.entity.WorkflowTask;
import travelcare_agent.workflow.event.TaskCreatedEvent;
import travelcare_agent.workflow.repository.WorkflowTaskRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WorkflowTaskServiceTest {

    private WorkflowTaskRepository repository;
    private ApplicationEventPublisher eventPublisher;
    private OutboxEventService outboxEventService;
    private WorkflowTaskService service;

    @BeforeEach
    void setUp() {
        repository = mock(WorkflowTaskRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        outboxEventService = mock(OutboxEventService.class);
        service = new WorkflowTaskService(repository, eventPublisher, outboxEventService, null);
    }

    @Test
    void create_ShouldInsertNewTaskInPendingState() {
        when(repository.insert(any(WorkflowTask.class))).thenAnswer(invocation -> {
            WorkflowTask task = invocation.getArgument(0);
            task.setId(1L);
            return task;
        });

        WorkflowTask task = service.createTask(100L, 200L, "ORDER_REFUND", "{}", "corr-123");

        assertThat(task.getId()).isNotNull();
        assertThat(task.getStatus()).isEqualTo(WorkflowTaskStatus.PENDING);
        assertThat(task.getAttemptCount()).isZero();
        assertThat(task.getMaxAttempts()).isEqualTo(3);
        verify(repository).insert(any(WorkflowTask.class));
        verify(eventPublisher).publishEvent(any(TaskCreatedEvent.class));
    }

    @Test
    void dispatchTaskIfPending_ShouldClaimCreateOutboxAndStoreMetadata() {
        WorkflowTask pending = task(WorkflowTaskStatus.PENDING, 0);
        WorkflowTask dispatched = task(WorkflowTaskStatus.DISPATCHED, 0);
        OutboxEvent event = new OutboxEvent();
        event.setId(77L);
        when(repository.findById(1L))
                .thenReturn(Optional.of(pending))
                .thenReturn(Optional.of(dispatched))
                .thenReturn(Optional.of(dispatched));
        when(repository.claimForDispatch(eq(1L), any(LocalDateTime.class))).thenReturn(1);
        when(outboxEventService.createOrReuse(any())).thenReturn(event);
        when(repository.updateLastOutboxEventIdIfCurrent(eq(1L), eq(null), eq(77L), anyList(), any()))
                .thenReturn(1);

        Optional<WorkflowTask> result = service.dispatchTaskIfPending(1L, "corr", "trace", "parent");

        assertThat(result).isPresent();
        verify(repository).claimForDispatch(eq(1L), any(LocalDateTime.class));
        verify(outboxEventService).createOrReuse(any());
        verify(repository).updateLastOutboxEventIdIfCurrent(eq(1L), eq(null), eq(77L),
                eq(List.of(WorkflowTaskStatus.DISPATCHED)), any(LocalDateTime.class));
    }

    @Test
    void dispatchTaskIfPending_ShouldReturnEmptyWhenClaimLosesRace() {
        WorkflowTask pending = task(WorkflowTaskStatus.PENDING, 0);
        when(repository.findById(1L)).thenReturn(Optional.of(pending));
        when(repository.claimForDispatch(eq(1L), any(LocalDateTime.class))).thenReturn(0);

        assertThat(service.dispatchTaskIfPending(1L, "corr", null, null)).isEmpty();

        verify(outboxEventService, never()).createOrReuse(any());
    }

    @Test
    void workerFailure_ShouldRetryToPendingAndIncrementAttemptInSql() {
        WorkflowTask dispatched = task(WorkflowTaskStatus.DISPATCHED, 0);
        WorkflowTask retried = task(WorkflowTaskStatus.PENDING, 1);
        when(repository.findById(1L)).thenReturn(Optional.of(dispatched), Optional.of(retried));
        when(repository.retryIfCurrent(eq(1L), eq(0), eq("ERR_1"), eq("Some error"),
                any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);

        WorkflowTask result = service.handleWorkerFailure(1L, "ERR_1", "Some error",
                LocalDateTime.now().plusMinutes(5), null);

        assertThat(result.getStatus()).isEqualTo(WorkflowTaskStatus.PENDING);
        assertThat(result.getAttemptCount()).isEqualTo(1);
        verify(repository).retryIfCurrent(eq(1L), eq(0), eq("ERR_1"), eq("Some error"),
                any(LocalDateTime.class), any(LocalDateTime.class));
    }

    @Test
    void workerFailure_ShouldFailAndCreateDeadLetter_WhenMaxAttemptsReached() {
        WorkflowTask dispatched = task(WorkflowTaskStatus.DISPATCHED, 2);
        dispatched.setMaxAttempts(3);
        WorkflowTask failed = task(WorkflowTaskStatus.FAILED, 3);
        failed.setDeadLetterReason("MAX_ATTEMPTS_REACHED");
        OutboxEvent event = new OutboxEvent();
        event.setId(88L);
        when(repository.findById(1L)).thenReturn(Optional.of(dispatched), Optional.of(failed), Optional.of(failed));
        when(repository.failIfCurrent(eq(1L), eq(2), eq("ERR_2"), eq("Fatal error"),
                eq("MAX_ATTEMPTS_REACHED"), any(LocalDateTime.class))).thenReturn(1);
        when(outboxEventService.createOrReuse(any())).thenReturn(event);
        when(repository.updateLastOutboxEventIdIfCurrent(eq(1L), eq(null), eq(88L), anyList(), any()))
                .thenReturn(1);

        WorkflowTask result = service.handleWorkerFailure(1L, "ERR_2", "Fatal error",
                LocalDateTime.now().plusMinutes(5), "trace");

        assertThat(result.getStatus()).isEqualTo(WorkflowTaskStatus.FAILED);
        assertThat(result.getAttemptCount()).isEqualTo(3);
        verify(repository).failIfCurrent(eq(1L), eq(2), eq("ERR_2"), eq("Fatal error"),
                eq("MAX_ATTEMPTS_REACHED"), any(LocalDateTime.class));
        verify(outboxEventService).createOrReuse(any());
    }

    @Test
    void terminalState_ShouldUseConditionalUpdateWithExpectedAttempt() {
        WorkflowTask succeeded = task(WorkflowTaskStatus.SUCCEEDED, 1);
        when(repository.markTerminalIfCurrentIn(eq(1L), eq(WorkflowTaskStatus.SUCCEEDED), anyList(),
                eq(1), any(LocalDateTime.class))).thenReturn(1);
        when(repository.findById(1L)).thenReturn(Optional.of(succeeded));

        WorkflowTask result = service.markTerminalState(1L, WorkflowTaskStatus.SUCCEEDED, 1);

        assertThat(result.getStatus()).isEqualTo(WorkflowTaskStatus.SUCCEEDED);
        verify(repository).markTerminalIfCurrentIn(eq(1L), eq(WorkflowTaskStatus.SUCCEEDED), anyList(),
                eq(1), any(LocalDateTime.class));
    }

    @Test
    void terminalState_ShouldThrowOnConditionalConflict() {
        when(repository.markTerminalIfCurrentIn(eq(1L), eq(WorkflowTaskStatus.SUCCEEDED), anyList(),
                eq(1), any(LocalDateTime.class))).thenReturn(0);

        assertThatThrownBy(() -> service.markTerminalState(1L, WorkflowTaskStatus.SUCCEEDED, 1))
                .isInstanceOf(WorkflowTaskStateConflictException.class);
    }

    private static WorkflowTask task(WorkflowTaskStatus status, int attemptCount) {
        WorkflowTask task = new WorkflowTask();
        task.setId(1L);
        task.setWorkflowId(10L);
        task.setSessionId(100L);
        task.setTaskType("order_refund_inquiry");
        task.setStatus(status);
        task.setPayloadJson("{\"message\":\"hello\"}");
        task.setAttemptCount(attemptCount);
        task.setMaxAttempts(3);
        return task;
    }
}
