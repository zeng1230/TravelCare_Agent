package travelcare_agent.workflow.repository;

import travelcare_agent.workflow.entity.WorkflowTask;
import travelcare_agent.enums.WorkflowTaskStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface WorkflowTaskRepository {
    WorkflowTask insert(WorkflowTask task);

    Optional<WorkflowTask> findById(Long id);

    Optional<WorkflowTask> findByWorkflowId(Long workflowId);

    List<WorkflowTask> findDispatchableTasks(LocalDateTime now, int limit);

    int claimForDispatch(Long id, LocalDateTime updatedAt);

    int retryIfCurrent(Long id, int expectedAttemptCount, String errorCode, String errorMessage,
                       LocalDateTime nextRunAt, LocalDateTime updatedAt);

    int failIfCurrent(Long id, int expectedAttemptCount, String errorCode, String errorMessage,
                      String deadLetterReason, LocalDateTime updatedAt);

    int markTerminalIfCurrentIn(Long id, WorkflowTaskStatus targetStatus,
                                List<WorkflowTaskStatus> allowedStatuses,
                                int expectedAttemptCount, LocalDateTime updatedAt);

    int updateLastOutboxEventIdIfCurrent(Long id, Long expectedLastOutboxEventId, Long lastOutboxEventId,
                                         List<WorkflowTaskStatus> allowedStatuses, LocalDateTime updatedAt);

    int recordSkippedReasonIfCurrent(Long id, String expectedLastSkippedReason, String skippedReason,
                                     List<WorkflowTaskStatus> allowedStatuses, LocalDateTime updatedAt);
}
