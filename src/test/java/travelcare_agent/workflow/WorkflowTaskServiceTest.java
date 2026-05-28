package travelcare_agent.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import travelcare_agent.enums.WorkflowTaskStatus;
import travelcare_agent.workflow.entity.WorkflowTask;
import travelcare_agent.workflow.repository.WorkflowTaskRepository;
import travelcare_agent.workflow.event.TaskCreatedEvent;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkflowTaskServiceTest {

    private WorkflowTaskRepository repository;
    private ApplicationEventPublisher eventPublisher;
    private WorkflowTaskService service;

    @BeforeEach
    void setUp() {
        repository = mock(WorkflowTaskRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new WorkflowTaskService(repository, eventPublisher);

        when(repository.save(any(WorkflowTask.class))).thenAnswer(invocation -> {
            WorkflowTask task = invocation.getArgument(0);
            if (task.getId() == null) {
                task.setId(1L);
            }
            return task;
        });
    }

    @Test
    void create_ShouldPersistNewTaskInPendingState() {
        WorkflowTask task = service.createTask(100L, 200L, "ORDER_REFUND", "{}", "corr-123");

        assertThat(task.getId()).isNotNull();
        assertThat(task.getStatus()).isEqualTo(WorkflowTaskStatus.PENDING);
        assertThat(task.getAttemptCount()).isEqualTo(0);
        assertThat(task.getMaxAttempts()).isEqualTo(3);

        ArgumentCaptor<WorkflowTask> captor = ArgumentCaptor.forClass(WorkflowTask.class);
        verify(repository).save(captor.capture());
        WorkflowTask saved = captor.getValue();
        assertThat(saved.getWorkflowId()).isEqualTo(100L);
        
        verify(eventPublisher).publishEvent(any(TaskCreatedEvent.class));
    }

    @Test
    void update_ShouldChangeStatus() {
        WorkflowTask task = new WorkflowTask();
        task.setId(1L);
        task.setStatus(WorkflowTaskStatus.PENDING);
        when(repository.findById(1L)).thenReturn(Optional.of(task));

        service.updateStatus(1L, WorkflowTaskStatus.RUNNING);

        ArgumentCaptor<WorkflowTask> captor = ArgumentCaptor.forClass(WorkflowTask.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(WorkflowTaskStatus.RUNNING);
    }

    @Test
    void retryIncrement_ShouldIncrementCountAndSetNextRun_WhenUnderMaxAttempts() {
        WorkflowTask task = new WorkflowTask();
        task.setId(1L);
        task.setAttemptCount(0);
        task.setMaxAttempts(3);
        when(repository.findById(1L)).thenReturn(Optional.of(task));

        LocalDateTime nextRun = LocalDateTime.now().plusMinutes(5);
        service.incrementRetry(1L, "ERR_1", "Some error", nextRun);

        ArgumentCaptor<WorkflowTask> captor = ArgumentCaptor.forClass(WorkflowTask.class);
        verify(repository).save(captor.capture());
        WorkflowTask saved = captor.getValue();
        assertThat(saved.getAttemptCount()).isEqualTo(1);
        assertThat(saved.getNextRunAt()).isEqualTo(nextRun);
        assertThat(saved.getLastErrorCode()).isEqualTo("ERR_1");
        assertThat(saved.getStatus()).isNotEqualTo(WorkflowTaskStatus.FAILED);
    }

    @Test
    void retryIncrement_ShouldTransitionToFailed_WhenMaxAttemptsReached() {
        WorkflowTask task = new WorkflowTask();
        task.setId(1L);
        task.setAttemptCount(2); // Next is 3 which is max
        task.setMaxAttempts(3);
        when(repository.findById(1L)).thenReturn(Optional.of(task));

        service.incrementRetry(1L, "ERR_2", "Fatal error", LocalDateTime.now().plusMinutes(5));

        ArgumentCaptor<WorkflowTask> captor = ArgumentCaptor.forClass(WorkflowTask.class);
        verify(repository).save(captor.capture());
        WorkflowTask saved = captor.getValue();
        assertThat(saved.getAttemptCount()).isEqualTo(3);
        assertThat(saved.getStatus()).isEqualTo(WorkflowTaskStatus.FAILED);
    }

    @Test
    void terminalState_ShouldTransitionCorrectly() {
        WorkflowTask task = new WorkflowTask();
        task.setId(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(task));

        service.markTerminalState(1L, WorkflowTaskStatus.SUCCEEDED);

        verify(repository).save(any(WorkflowTask.class));
        assertThat(task.getStatus()).isEqualTo(WorkflowTaskStatus.SUCCEEDED);
    }
}
