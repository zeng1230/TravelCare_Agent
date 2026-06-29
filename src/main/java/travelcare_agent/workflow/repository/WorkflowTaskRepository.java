package travelcare_agent.workflow.repository;

import travelcare_agent.workflow.entity.WorkflowTask;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface WorkflowTaskRepository {
    WorkflowTask save(WorkflowTask task);

    Optional<WorkflowTask> findById(Long id);

    Optional<WorkflowTask> findByWorkflowId(Long workflowId);

    List<WorkflowTask> findPendingTasksCreatedBefore(LocalDateTime dateTime);
}

