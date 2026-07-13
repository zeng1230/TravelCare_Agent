package travelcare_agent.workflow.repository.mybatis;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Repository;
import travelcare_agent.enums.WorkflowTaskStatus;
import travelcare_agent.workflow.entity.WorkflowTask;
import travelcare_agent.workflow.repository.WorkflowTaskRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class MyBatisWorkflowTaskRepository implements WorkflowTaskRepository {

    private final MyBatisWorkflowTaskMapper mapper;

    public MyBatisWorkflowTaskRepository(MyBatisWorkflowTaskMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public WorkflowTask insert(WorkflowTask task) {
        if (task.getCreatedAt() == null) {
            task.setCreatedAt(LocalDateTime.now());
        }
        if (task.getUpdatedAt() == null) {
            task.setUpdatedAt(LocalDateTime.now());
        }
        mapper.insert(task);
        return task;
    }

    @Override
    public Optional<WorkflowTask> findById(Long id) {
        return Optional.ofNullable(mapper.selectById(id));
    }

    @Override
    public Optional<WorkflowTask> findByWorkflowId(Long workflowId) {
        return mapper.selectList(new LambdaQueryWrapper<WorkflowTask>()
                        .eq(WorkflowTask::getWorkflowId, workflowId))
                .stream()
                .findFirst();
    }

    @Override
    public List<WorkflowTask> findDispatchableTasks(LocalDateTime now, int limit) {
        return mapper.selectList(new LambdaQueryWrapper<WorkflowTask>()
                .eq(WorkflowTask::getStatus, travelcare_agent.enums.WorkflowTaskStatus.PENDING)
                .and(wrapper -> wrapper.isNull(WorkflowTask::getNextRunAt)
                        .or().le(WorkflowTask::getNextRunAt, now))
                .orderByAsc(WorkflowTask::getCreatedAt)
                .last("limit " + Math.max(1, limit)));
    }

    @Override
    public int claimForDispatch(Long id, LocalDateTime updatedAt) {
        return mapper.claimForDispatch(id, updatedAt);
    }

    @Override
    public int retryIfCurrent(Long id, int expectedAttemptCount, String errorCode, String errorMessage,
                              LocalDateTime nextRunAt, LocalDateTime updatedAt) {
        return mapper.retryIfCurrent(id, expectedAttemptCount, errorCode, errorMessage, nextRunAt, updatedAt);
    }

    @Override
    public int failIfCurrent(Long id, int expectedAttemptCount, String errorCode, String errorMessage,
                             String deadLetterReason, LocalDateTime updatedAt) {
        return mapper.failIfCurrent(id, expectedAttemptCount, errorCode, errorMessage, deadLetterReason, updatedAt);
    }

    @Override
    public int markTerminalIfCurrentIn(Long id, WorkflowTaskStatus targetStatus,
                                       List<WorkflowTaskStatus> allowedStatuses,
                                       int expectedAttemptCount, LocalDateTime updatedAt) {
        return mapper.markTerminalIfCurrentIn(id, targetStatus, allowedStatuses, expectedAttemptCount, updatedAt);
    }

    @Override
    public int updateLastOutboxEventIdIfCurrent(Long id, Long expectedLastOutboxEventId, Long lastOutboxEventId,
                                                List<WorkflowTaskStatus> allowedStatuses, LocalDateTime updatedAt) {
        return mapper.updateLastOutboxEventIdIfCurrent(id, expectedLastOutboxEventId, lastOutboxEventId,
                allowedStatuses, updatedAt);
    }

    @Override
    public int recordSkippedReasonIfCurrent(Long id, String expectedLastSkippedReason, String skippedReason,
                                            List<WorkflowTaskStatus> allowedStatuses, LocalDateTime updatedAt) {
        return mapper.recordSkippedReasonIfCurrent(id, expectedLastSkippedReason, skippedReason,
                allowedStatuses, updatedAt);
    }
}
