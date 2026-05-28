package travelcare_agent.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import travelcare_agent.audit.AuditService;
import travelcare_agent.enums.WorkflowTaskStatus;
import travelcare_agent.workflow.entity.WorkflowTask;
import travelcare_agent.workflow.event.TaskCreatedEvent;
import travelcare_agent.workflow.repository.WorkflowTaskRepository;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkflowTaskSchedulerTest {

    private WorkflowTaskRepository taskRepository;
    private WorkflowTaskPublisher taskPublisher;
    private AuditService auditService;
    private WorkflowTaskScheduler scheduler;

    @BeforeEach
    void setUp() {
        taskRepository = mock(WorkflowTaskRepository.class);
        taskPublisher = mock(WorkflowTaskPublisher.class);
        auditService = mock(AuditService.class);
        scheduler = new WorkflowTaskScheduler(taskRepository, taskPublisher, auditService);
    }

    @Test
    void processPendingTasks_ShouldDoNothing_WhenNoPendingTasksExist() {
        when(taskRepository.findPendingTasksCreatedBefore(any(LocalDateTime.class)))
                .thenReturn(Collections.emptyList());

        scheduler.processPendingTasks();

        verify(taskRepository, never()).save(any(WorkflowTask.class));
        verify(taskPublisher, never()).onTaskCreated(any(TaskCreatedEvent.class));
    }

    @Test
    void processPendingTasks_ShouldRedispatch_WhenAttemptCountUnderMax() {
        WorkflowTask task = new WorkflowTask();
        task.setId(101L);
        task.setSessionId(201L);
        task.setWorkflowId(301L);
        task.setStatus(WorkflowTaskStatus.PENDING);
        task.setAttemptCount(0);
        task.setMaxAttempts(3);

        when(taskRepository.findPendingTasksCreatedBefore(any(LocalDateTime.class)))
                .thenReturn(List.of(task));

        scheduler.processPendingTasks();

        // Check if task is updated and saved
        ArgumentCaptor<WorkflowTask> taskCaptor = ArgumentCaptor.forClass(WorkflowTask.class);
        verify(taskRepository).save(taskCaptor.capture());
        WorkflowTask savedTask = taskCaptor.getValue();
        assertThat(savedTask.getAttemptCount()).isEqualTo(1);
        assertThat(savedTask.getStatus()).isEqualTo(WorkflowTaskStatus.PENDING);

        // Check if publisher is triggered
        ArgumentCaptor<TaskCreatedEvent> eventCaptor = ArgumentCaptor.forClass(TaskCreatedEvent.class);
        verify(taskPublisher).onTaskCreated(eventCaptor.capture());
        TaskCreatedEvent publishedEvent = eventCaptor.getValue();
        assertThat(publishedEvent.getTaskId()).isEqualTo(101L);
        assertThat(publishedEvent.getSessionId()).isEqualTo(201L);
        assertThat(publishedEvent.getWorkflowId()).isEqualTo(301L);
        assertThat(publishedEvent.getCorrelationId()).isNotNull();

        verify(auditService, never()).recordTaskFailure(any(), any(), any(), any());
    }

    @Test
    void processPendingTasks_ShouldMarkAsFailedAndAudit_WhenMaxAttemptsExceeded() {
        WorkflowTask task = new WorkflowTask();
        task.setId(102L);
        task.setSessionId(202L);
        task.setWorkflowId(302L);
        task.setStatus(WorkflowTaskStatus.PENDING);
        task.setAttemptCount(2); // Next increment makes it 3, which is maxAttempts
        task.setMaxAttempts(3);

        when(taskRepository.findPendingTasksCreatedBefore(any(LocalDateTime.class)))
                .thenReturn(List.of(task));

        scheduler.processPendingTasks();

        // Check if task is updated to FAILED and saved
        ArgumentCaptor<WorkflowTask> taskCaptor = ArgumentCaptor.forClass(WorkflowTask.class);
        verify(taskRepository).save(taskCaptor.capture());
        WorkflowTask savedTask = taskCaptor.getValue();
        assertThat(savedTask.getAttemptCount()).isEqualTo(3);
        assertThat(savedTask.getStatus()).isEqualTo(WorkflowTaskStatus.FAILED);

        // Check if failure is audited
        verify(auditService).recordTaskFailure(
                eq(202L),
                eq(302L),
                eq(102L),
                contains("Max attempts")
        );

        // Publisher should NOT be triggered
        verify(taskPublisher, never()).onTaskCreated(any(TaskCreatedEvent.class));
    }
}
