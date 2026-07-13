package travelcare_agent.workflow.repository.mybatis;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import travelcare_agent.enums.WorkflowTaskStatus;
import travelcare_agent.workflow.entity.WorkflowTask;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface MyBatisWorkflowTaskMapper extends BaseMapper<WorkflowTask> {
    @Update("UPDATE workflow_tasks SET status='DISPATCHED', updated_at=#{updatedAt} " +
            "WHERE id=#{id} AND status='PENDING'")
    int claimForDispatch(@Param("id") Long id, @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE workflow_tasks SET status='PENDING', attempt_count=attempt_count+1, " +
            "last_error_code=#{errorCode}, last_error_message=#{errorMessage}, next_run_at=#{nextRunAt}, " +
            "updated_at=#{updatedAt} WHERE id=#{id} AND status IN ('PENDING','DISPATCHED','RUNNING') " +
            "AND attempt_count=#{expectedAttemptCount}")
    int retryIfCurrent(@Param("id") Long id,
                       @Param("expectedAttemptCount") int expectedAttemptCount,
                       @Param("errorCode") String errorCode,
                       @Param("errorMessage") String errorMessage,
                       @Param("nextRunAt") LocalDateTime nextRunAt,
                       @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE workflow_tasks SET status='FAILED', attempt_count=attempt_count+1, " +
            "last_error_code=#{errorCode}, last_error_message=#{errorMessage}, " +
            "dead_letter_reason=#{deadLetterReason}, next_run_at=NULL, updated_at=#{updatedAt} " +
            "WHERE id=#{id} AND status IN ('PENDING','DISPATCHED','RUNNING') " +
            "AND attempt_count=#{expectedAttemptCount}")
    int failIfCurrent(@Param("id") Long id,
                      @Param("expectedAttemptCount") int expectedAttemptCount,
                      @Param("errorCode") String errorCode,
                      @Param("errorMessage") String errorMessage,
                      @Param("deadLetterReason") String deadLetterReason,
                      @Param("updatedAt") LocalDateTime updatedAt);

    @Update({"<script>",
            "UPDATE workflow_tasks SET status=#{targetStatus}, updated_at=#{updatedAt}",
            "WHERE id=#{id} AND attempt_count=#{expectedAttemptCount} AND status IN",
            "<foreach collection='allowedStatuses' item='status' open='(' separator=',' close=')'>#{status}</foreach>",
            "</script>"})
    int markTerminalIfCurrentIn(@Param("id") Long id,
                                @Param("targetStatus") WorkflowTaskStatus targetStatus,
                                @Param("allowedStatuses") List<WorkflowTaskStatus> allowedStatuses,
                                @Param("expectedAttemptCount") int expectedAttemptCount,
                                @Param("updatedAt") LocalDateTime updatedAt);

    @Update({"<script>",
            "UPDATE workflow_tasks SET last_outbox_event_id=#{lastOutboxEventId}, updated_at=#{updatedAt}",
            "WHERE id=#{id} AND last_outbox_event_id &lt;=&gt; #{expectedLastOutboxEventId} AND status IN",
            "<foreach collection='allowedStatuses' item='status' open='(' separator=',' close=')'>#{status}</foreach>",
            "</script>"})
    int updateLastOutboxEventIdIfCurrent(@Param("id") Long id,
                                         @Param("expectedLastOutboxEventId") Long expectedLastOutboxEventId,
                                         @Param("lastOutboxEventId") Long lastOutboxEventId,
                                         @Param("allowedStatuses") List<WorkflowTaskStatus> allowedStatuses,
                                         @Param("updatedAt") LocalDateTime updatedAt);

    @Update({"<script>",
            "UPDATE workflow_tasks SET last_skipped_reason=#{skippedReason}, updated_at=#{updatedAt}",
            "WHERE id=#{id} AND last_skipped_reason &lt;=&gt; #{expectedLastSkippedReason} AND status IN",
            "<foreach collection='allowedStatuses' item='status' open='(' separator=',' close=')'>#{status}</foreach>",
            "</script>"})
    int recordSkippedReasonIfCurrent(@Param("id") Long id,
                                     @Param("expectedLastSkippedReason") String expectedLastSkippedReason,
                                     @Param("skippedReason") String skippedReason,
                                     @Param("allowedStatuses") List<WorkflowTaskStatus> allowedStatuses,
                                     @Param("updatedAt") LocalDateTime updatedAt);
}
