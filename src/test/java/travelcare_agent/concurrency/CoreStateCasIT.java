package travelcare_agent.concurrency;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import travelcare_agent.enums.*;
import travelcare_agent.human.entity.HumanReviewCase;
import travelcare_agent.human.repository.HumanReviewCaseRepository;
import travelcare_agent.refund.entity.RefundCase;
import travelcare_agent.refund.repository.RefundCaseRepository;
import travelcare_agent.workflow.entity.Workflow;
import travelcare_agent.workflow.repository.WorkflowRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(properties = {"spring.rabbitmq.listener.simple.auto-startup=false",
        "travelcare.agent.provider=mock", "travelcare.jwt.secret=test-jwt-secret-with-at-least-32-bytes"})
class CoreStateCasIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("travelcare_agent").withUsername("test").withPassword("test");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
    }
    @Autowired WorkflowRepository workflows;
    @Autowired HumanReviewCaseRepository reviews;
    @Autowired RefundCaseRepository refunds;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;

    @BeforeEach void clean() {
        jdbc.update("DELETE FROM audit_logs");
        jdbc.update("DELETE FROM session_events");
        jdbc.update("DELETE FROM human_review_cases");
        jdbc.update("DELETE FROM refund_cases");
        jdbc.update("DELETE FROM workflows");
    }

    @Test void laterCasConflictRollsBackEarlierSuccessfulCasAndLeavesNoSideEffects() {
        Workflow w = workflows.insert(Workflow.create(61L, "order_refund_inquiry"));
        w.transitionTo(WorkflowStatus.RUNNING, "RUNNING", "{}");
        assertThat(workflows.transitionIfCurrent(w, 0, List.of(WorkflowStatus.CREATED))).isEqualTo(1);
        HumanReviewCase c = review("tenant-a", 61L, w.getId());
        reviews.insert(c);

        assertThatThrownBy(() -> new TransactionTemplate(txManager).executeWithoutResult(status -> {
            Workflow current = workflows.findById(w.getId()).orElseThrow();
            current.transitionTo(WorkflowStatus.RESPONDED, "RESOLVED", "{}");
            if (workflows.transitionIfCurrent(current, 1, List.of(WorkflowStatus.RUNNING)) != 1)
                throw new IllegalStateException("first CAS did not win");
            HumanReviewCase review = reviews.findByIdAndTenantId(c.getId(), "tenant-a").orElseThrow();
            review.setStatus(HumanReviewCaseStatus.RESOLVED);
            if (reviews.resolveIfCurrent(review, 99) != 1)
                throw new IllegalStateException("forced concurrent conflict");
        })).isInstanceOf(IllegalStateException.class).hasMessage("forced concurrent conflict");

        Workflow stored = workflows.findById(w.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(stored.getVersion()).isEqualTo(1);
        HumanReviewCase storedReview = reviews.findByIdAndTenantId(c.getId(), "tenant-a").orElseThrow();
        assertThat(storedReview.getStatus()).isEqualTo(HumanReviewCaseStatus.OPEN);
        assertThat(storedReview.getVersion()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM session_events", Integer.class)).isZero();
    }

    @Test void workflowCasChecksVersionAndStateAndIncrementsExactlyOnce() {
        Workflow w = workflows.insert(Workflow.create(11L, "order_refund_inquiry"));
        w.transitionTo(WorkflowStatus.RUNNING, "START", "{\"phase\":1}");
        assertThat(workflows.transitionIfCurrent(w, 0, List.of(WorkflowStatus.CREATED))).isEqualTo(1);
        assertThat(workflows.findById(w.getId()).orElseThrow().getVersion()).isEqualTo(1);
        w.transitionTo(WorkflowStatus.FAILED, "FAILED", "{}");
        assertThat(workflows.transitionIfCurrent(w, 0, List.of(WorkflowStatus.RUNNING))).isZero();
        assertThat(workflows.transitionIfCurrent(w, 1, List.of(WorkflowStatus.CREATED))).isZero();
        Workflow stored = workflows.findById(w.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(stored.getVersion()).isEqualTo(1);
    }

    @Test void reviewCasChecksTenantVersionAndAllowedState() {
        HumanReviewCase c = review("tenant-a", 21L, 31L);
        reviews.insert(c); c.setStatus(HumanReviewCaseStatus.ASSIGNED); c.setAssignedTo("7");
        assertThat(reviews.assignIfOpen(c, 0)).isEqualTo(1);
        assertThat(reviews.assignIfOpen(c, 0)).isZero();
        c.setTenantId("tenant-b");
        assertThat(reviews.resolveIfCurrent(c, 1)).isZero();
        HumanReviewCase stored = reviews.findByIdAndTenantId(c.getId(), "tenant-a").orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(HumanReviewCaseStatus.ASSIGNED);
        assertThat(stored.getVersion()).isEqualTo(1);
    }

    @Test void refundCasChecksTenantWorkflowVersionAndNeedHumanState() {
        RefundCase c = RefundCase.create(1L, 2L, 41L, RefundCaseStatus.NEED_HUMAN,
                new BigDecimal("10.00"), "manual", "{}");
        c.setTenantId("tenant-a"); refunds.insert(c); c.setStatus(RefundCaseStatus.ELIGIBLE);
        assertThat(refunds.decideIfNeedHuman(c, 0)).isEqualTo(1);
        assertThat(refunds.decideIfNeedHuman(c, 0)).isZero();
        c.setWorkflowId(42L);
        assertThat(refunds.decideIfNeedHuman(c, 1)).isZero();
        RefundCase stored = refunds.findById(c.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(RefundCaseStatus.ELIGIBLE);
        assertThat(stored.getVersion()).isEqualTo(1);
    }

    @Test void twoIndependentTransactionsReadingSameVersionProduceOneWinner() throws Exception {
        Workflow seed = workflows.insert(Workflow.create(51L, "order_refund_inquiry"));
        seed.transitionTo(WorkflowStatus.RUNNING, "RUNNING", "{}");
        assertThat(workflows.transitionIfCurrent(seed, 0, List.of(WorkflowStatus.CREATED))).isEqualTo(1);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit(() -> compete(seed.getId(), WorkflowStatus.RESPONDED, barrier));
            Future<Integer> b = pool.submit(() -> compete(seed.getId(), WorkflowStatus.FAILED, barrier));
            assertThat(a.get(10, TimeUnit.SECONDS) + b.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            Workflow stored = workflows.findById(seed.getId()).orElseThrow();
            assertThat(stored.getStatus()).isIn(WorkflowStatus.RESPONDED, WorkflowStatus.FAILED);
            assertThat(stored.getVersion()).isEqualTo(2);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void approvedAndRejectedTransactionsProduceOneConsistentDecisionAndOneSideEffectSet() throws Exception {
        Workflow w = workflows.insert(Workflow.create(71L, "order_refund_inquiry"));
        w.transitionTo(WorkflowStatus.NEED_HUMAN, "NEED_HUMAN", "{}");
        assertThat(workflows.transitionIfCurrent(w, 0, List.of(WorkflowStatus.CREATED))).isEqualTo(1);
        RefundCase refund = RefundCase.create(1L, 2L, w.getId(), RefundCaseStatus.NEED_HUMAN,
                new BigDecimal("20.00"), "manual", "{}");
        refund.setTenantId("tenant-a"); refunds.insert(refund);
        HumanReviewCase review = review("tenant-a", 71L, w.getId());
        review.setRefundCaseId(refund.getId()); reviews.insert(review);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> approved = pool.submit(() -> decide(review.getId(), true, barrier));
            Future<Integer> rejected = pool.submit(() -> decide(review.getId(), false, barrier));
            assertThat(approved.get(10, TimeUnit.SECONDS) + rejected.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        Workflow storedWorkflow = workflows.findById(w.getId()).orElseThrow();
        HumanReviewCase storedReview = reviews.findByIdAndTenantId(review.getId(), "tenant-a").orElseThrow();
        RefundCase storedRefund = refunds.findById(refund.getId()).orElseThrow();
        assertThat(storedReview.getStatus()).isEqualTo(HumanReviewCaseStatus.RESOLVED);
        if (storedReview.getResolution() == HumanReviewResolution.APPROVED) {
            assertThat(storedWorkflow.getStatus()).isEqualTo(WorkflowStatus.RESPONDED);
            assertThat(storedRefund.getStatus()).isEqualTo(RefundCaseStatus.ELIGIBLE);
        } else {
            assertThat(storedWorkflow.getStatus()).isEqualTo(WorkflowStatus.FAILED);
            assertThat(storedRefund.getStatus()).isEqualTo(RefundCaseStatus.INELIGIBLE);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM session_events", Integer.class)).isEqualTo(1);
    }

    private int compete(Long id, WorkflowStatus target, CyclicBarrier barrier) {
        return new TransactionTemplate(txManager).execute(status -> {
            Workflow w = workflows.findById(id).orElseThrow(); long expected = w.getVersion();
            try { barrier.await(5, TimeUnit.SECONDS); } catch (Exception e) { throw new RuntimeException(e); }
            w.transitionTo(target, target.name(), "{}");
            return workflows.transitionIfCurrent(w, expected, List.of(WorkflowStatus.RUNNING));
        });
    }

    private int decide(Long reviewId, boolean approved, CyclicBarrier barrier) {
        return new TransactionTemplate(txManager).execute(status -> {
            HumanReviewCase review = reviews.findByIdAndTenantId(reviewId, "tenant-a").orElseThrow();
            Workflow workflow = workflows.findById(review.getWorkflowId()).orElseThrow();
            RefundCase refund = refunds.findById(review.getRefundCaseId()).orElseThrow();
            try { barrier.await(5, TimeUnit.SECONDS); } catch (Exception e) { throw new RuntimeException(e); }
            WorkflowStatus workflowTarget = approved ? WorkflowStatus.RESPONDED : WorkflowStatus.FAILED;
            workflow.transitionTo(workflowTarget, "RESOLVED", "{}");
            if (workflows.transitionIfCurrent(workflow, workflow.getVersion(), List.of(WorkflowStatus.NEED_HUMAN)) != 1)
                return 0;
            review.setStatus(HumanReviewCaseStatus.RESOLVED);
            review.setResolution(approved ? HumanReviewResolution.APPROVED : HumanReviewResolution.REJECTED);
            review.setResolvedBy("7"); review.setResolvedAt(LocalDateTime.now()); review.setUpdatedAt(LocalDateTime.now());
            if (reviews.resolveIfCurrent(review, review.getVersion()) != 1) throw new IllegalStateException("review CAS");
            refund.setStatus(approved ? RefundCaseStatus.ELIGIBLE : RefundCaseStatus.INELIGIBLE);
            refund.setUpdatedAt(LocalDateTime.now());
            if (refunds.decideIfNeedHuman(refund, refund.getVersion()) != 1) throw new IllegalStateException("refund CAS");
            long suffix = approved ? 1 : 2;
            jdbc.update("INSERT INTO audit_logs(id,tenant_id,actor_type,actor_id,session_id,workflow_id,action,target_type,target_id,evidence_json,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,NOW())",
                    9000L + suffix, "tenant-a", "OPERATOR", "7", 71L, workflow.getId(), "RESOLVE", "HUMAN_REVIEW_CASE", reviewId, "{}");
            jdbc.update("INSERT INTO session_events(id,session_id,seq_no,event_type,role,content,metadata_json,created_at) VALUES(?,?,?,?,?,?,?,NOW())",
                    9100L + suffix, 71L, 1, "MESSAGE", "ASSISTANT", approved ? "approved" : "rejected", "{}");
            return 1;
        });
    }

    private static HumanReviewCase review(String tenant, Long sessionId, Long workflowId) {
        HumanReviewCase c = new HumanReviewCase();
        c.setTenantId(tenant); c.setSessionId(sessionId); c.setWorkflowId(workflowId);
        c.setCaseType("REFUND_REVIEW"); c.setStatus(HumanReviewCaseStatus.OPEN); c.setVersion(0L);
        c.setPriority("HIGH"); c.setReasonCode("MANUAL"); c.setEvidenceJson("{}");
        c.setCreatedAt(LocalDateTime.now()); c.setUpdatedAt(LocalDateTime.now()); return c;
    }
}
