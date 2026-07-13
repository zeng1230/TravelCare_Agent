package travelcare_agent.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import travelcare_agent.audit.AuditService;
import travelcare_agent.enums.WorkflowTaskStatus;
import travelcare_agent.workflow.entity.WorkflowTask;
import travelcare_agent.workflow.repository.WorkflowTaskRepository;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class WorkflowTaskSchedulerTest {

    private WorkflowTaskRepository taskRepository;
    private WorkflowTaskService taskService;
    private AuditService auditService;
    private WorkflowTaskScheduler scheduler;

    @BeforeEach
    void setUp() {
        taskRepository = mock(WorkflowTaskRepository.class);
        taskService = mock(WorkflowTaskService.class);
        auditService = mock(AuditService.class);
        scheduler = new WorkflowTaskScheduler(taskRepository, taskService, auditService);
    }

    @Test
    void processPendingTasks_ShouldDoNothing_WhenNoDispatchableTasksExist() {
        when(taskRepository.findDispatchableTasks(any(LocalDateTime.class), anyInt()))
                .thenReturn(Collections.emptyList());

        scheduler.processPendingTasks();

        verify(taskService, never()).dispatchTaskIfPending(any(), anyString(), any(), any());
    }

    @Test
    void processPendingTasks_ShouldAuditDispatchOnlyWhenClaimSucceeds() {
        WorkflowTask task = task(101L);
        when(taskRepository.findDispatchableTasks(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(task));
        when(taskService.dispatchTaskIfPending(eq(101L), anyString(), any(), any()))
                .thenReturn(Optional.of(task));

        scheduler.processPendingTasks();

        verify(taskService).dispatchTaskIfPending(eq(101L), anyString(), any(), any());
        verify(auditService).recordTaskDispatch(201L, 301L, 101L);
        verify(auditService, never()).recordTaskFailure(any(), any(), any(), any());
    }

    @Test
    void processPendingTasks_ShouldNotAudit_WhenClaimLosesRace() {
        WorkflowTask task = task(101L);
        when(taskRepository.findDispatchableTasks(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(task));
        when(taskService.dispatchTaskIfPending(eq(101L), anyString(), any(), any()))
                .thenReturn(Optional.empty());

        scheduler.processPendingTasks();

        verify(auditService, never()).recordTaskDispatch(any(), any(), any());
        verify(auditService, never()).recordTaskFailure(any(), any(), any(), any());
    }

    @Test
    void processPendingTasks_ShouldAuditFailure_WhenDispatchThrows() {
        WorkflowTask task = task(102L);
        when(taskRepository.findDispatchableTasks(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(task));
        when(taskService.dispatchTaskIfPending(eq(102L), anyString(), any(), any()))
                .thenThrow(new RuntimeException("outbox down"));

        scheduler.processPendingTasks();

        verify(auditService).recordTaskFailure(eq(201L), eq(301L), eq(102L),
                contains("Failed to dispatch"));
        verify(auditService, never()).recordTaskDispatch(any(), any(), any());
    }

    private static WorkflowTask task(Long id) {
        WorkflowTask task = new WorkflowTask();
        task.setId(id);
        task.setSessionId(201L);
        task.setWorkflowId(301L);
        task.setStatus(WorkflowTaskStatus.PENDING);
        task.setAttemptCount(0);
        task.setMaxAttempts(3);
        return task;
    }
}
