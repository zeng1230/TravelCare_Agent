package travelcare_agent.concurrency;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import travelcare_agent.enums.WorkflowTaskStatus;
import travelcare_agent.outbox.OutboxEventService;
import travelcare_agent.workflow.WorkflowTaskService;
import travelcare_agent.workflow.entity.WorkflowTask;
import travelcare_agent.workflow.repository.WorkflowTaskRepository;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@Testcontainers
@SpringBootTest(properties = {"spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.task.scheduling.enabled=false",
        "travelcare.agent.provider=mock", "travelcare.jwt.secret=test-jwt-secret-with-at-least-32-bytes"})
class WorkflowTaskCasIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("travelcare_agent").withUsername("test").withPassword("test");

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired WorkflowTaskRepository tasks;
    @Autowired WorkflowTaskService taskService;
    @Autowired JdbcTemplate jdbc;
    @SpyBean OutboxEventService outboxEventService;

    @BeforeEach void clean() {
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM workflow_tasks");
    }

    @AfterEach void resetSpy() {
        Mockito.reset(outboxEventService);
    }

    @Test void nextRunAtInFutureIsNotDispatchable() {
        WorkflowTask task = task(WorkflowTaskStatus.PENDING, 0);
        task.setNextRunAt(LocalDateTime.now().plusMinutes(5));
        tasks.insert(task);

        assertThat(tasks.findDispatchableTasks(LocalDateTime.now(), 10)).isEmpty();
    }

    @Test void duePendingTaskIsDispatchable() {
        WorkflowTask task = task(WorkflowTaskStatus.PENDING, 0);
        task.setNextRunAt(LocalDateTime.now().minusSeconds(1));
        tasks.insert(task);

        assertThat(tasks.findDispatchableTasks(LocalDateTime.now(), 10))
                .extracting(WorkflowTask::getId)
                .containsExactly(task.getId());
    }

    @Test void twoSchedulersCanOnlyClaimPendingTaskOnce() {
        WorkflowTask task = tasks.insert(task(WorkflowTaskStatus.PENDING, 0));

        int first = tasks.claimForDispatch(task.getId(), LocalDateTime.now());
        int second = tasks.claimForDispatch(task.getId(), LocalDateTime.now());

        assertThat(first + second).isEqualTo(1);
        assertThat(tasks.findById(task.getId()).orElseThrow().getStatus())
                .isEqualTo(WorkflowTaskStatus.DISPATCHED);
    }

    @Test void workerRetryReturnsTaskToPendingAndIncrementsAttemptOnce() {
        WorkflowTask task = tasks.insert(task(WorkflowTaskStatus.DISPATCHED, 0));
        LocalDateTime nextRunAt = LocalDateTime.now().plusMinutes(1);

        WorkflowTask retried = taskService.handleWorkerFailure(task.getId(), "LOCK_CONFLICT",
                "Could not acquire lock", nextRunAt, null);

        assertThat(retried.getStatus()).isEqualTo(WorkflowTaskStatus.PENDING);
        assertThat(retried.getAttemptCount()).isEqualTo(1);
        assertThat(retried.getNextRunAt()).isNotNull();
    }

    @Test void schedulerRedispatchDoesNotIncrementAttemptCount() {
        WorkflowTask task = task(WorkflowTaskStatus.PENDING, 2);
        task.setNextRunAt(LocalDateTime.now().minusSeconds(1));
        tasks.insert(task);

        assertThat(taskService.dispatchTaskIfPending(task.getId(), "corr", null, null)).isPresent();

        WorkflowTask dispatched = tasks.findById(task.getId()).orElseThrow();
        assertThat(dispatched.getStatus()).isEqualTo(WorkflowTaskStatus.DISPATCHED);
        assertThat(dispatched.getAttemptCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isEqualTo(1);
    }

    @Test void outboxCreationFailureRollsBackDispatchClaim() {
        WorkflowTask task = tasks.insert(task(WorkflowTaskStatus.PENDING, 0));
        doThrow(new RuntimeException("outbox down")).when(outboxEventService).createOrReuse(any());

        assertThatThrownBy(() -> taskService.dispatchTaskIfPending(task.getId(), "corr", null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("outbox down");

        WorkflowTask stored = tasks.findById(task.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(WorkflowTaskStatus.PENDING);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isZero();
    }

    @Test void terminalTasksDoNotMoveBackToActiveStates() {
        for (WorkflowTaskStatus terminal : List.of(WorkflowTaskStatus.SUCCEEDED, WorkflowTaskStatus.CANCELLED,
                WorkflowTaskStatus.NEED_HUMAN, WorkflowTaskStatus.FAILED)) {
            WorkflowTask task = tasks.insert(task(terminal, 0));
            assertThat(tasks.claimForDispatch(task.getId(), LocalDateTime.now())).isZero();
            assertThat(tasks.retryIfCurrent(task.getId(), 0, "ERR", "retry",
                    LocalDateTime.now(), LocalDateTime.now())).isZero();
            assertThat(tasks.markTerminalIfCurrentIn(task.getId(), WorkflowTaskStatus.RUNNING,
                    List.of(WorkflowTaskStatus.PENDING, WorkflowTaskStatus.DISPATCHED, WorkflowTaskStatus.RUNNING),
                    0, LocalDateTime.now())).isZero();
        }
    }

    @Test void attemptComparePreventsStaleRetryCompetition() {
        WorkflowTask task = tasks.insert(task(WorkflowTaskStatus.DISPATCHED, 0));

        int first = tasks.retryIfCurrent(task.getId(), 0, "ERR", "first",
                LocalDateTime.now().plusMinutes(1), LocalDateTime.now());
        int second = tasks.retryIfCurrent(task.getId(), 0, "ERR", "second",
                LocalDateTime.now().plusMinutes(1), LocalDateTime.now());

        WorkflowTask stored = tasks.findById(task.getId()).orElseThrow();
        assertThat(first + second).isEqualTo(1);
        assertThat(stored.getAttemptCount()).isEqualTo(1);
        assertThat(stored.getLastErrorMessage()).isEqualTo("first");
    }

    @Test void staleMetadataUpdatesCannotOverwriteNewerValues() {
        WorkflowTask task = tasks.insert(task(WorkflowTaskStatus.DISPATCHED, 0));
        List<WorkflowTaskStatus> statuses = List.of(WorkflowTaskStatus.DISPATCHED);

        assertThat(tasks.updateLastOutboxEventIdIfCurrent(task.getId(), null, 1L, statuses,
                LocalDateTime.now())).isEqualTo(1);
        assertThat(tasks.updateLastOutboxEventIdIfCurrent(task.getId(), null, 2L, statuses,
                LocalDateTime.now())).isZero();
        assertThat(tasks.recordSkippedReasonIfCurrent(task.getId(), null, "FIRST", statuses,
                LocalDateTime.now())).isEqualTo(1);
        assertThat(tasks.recordSkippedReasonIfCurrent(task.getId(), null, "SECOND", statuses,
                LocalDateTime.now())).isZero();

        WorkflowTask stored = tasks.findById(task.getId()).orElseThrow();
        assertThat(stored.getLastOutboxEventId()).isEqualTo(1L);
        assertThat(stored.getLastSkippedReason()).isEqualTo("FIRST");
    }

    private static WorkflowTask task(WorkflowTaskStatus status, int attemptCount) {
        WorkflowTask task = new WorkflowTask();
        task.setWorkflowId(10L);
        task.setSessionId(20L);
        task.setTaskType("order_refund_inquiry");
        task.setStatus(status);
        task.setPayloadJson("{\"message\":\"hello\"}");
        task.setAttemptCount(attemptCount);
        task.setMaxAttempts(3);
        return task;
    }
}
