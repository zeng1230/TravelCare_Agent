package travelcare_agent.human;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.common.result.ResultCode;
import travelcare_agent.conversation.repository.SessionRepository;
import travelcare_agent.conversation.service.SessionService;
import travelcare_agent.enums.HumanReviewCaseStatus;
import travelcare_agent.enums.HumanReviewResolution;
import travelcare_agent.enums.RefundCaseStatus;
import travelcare_agent.enums.WorkflowStatus;
import travelcare_agent.human.entity.HumanReviewCase;
import travelcare_agent.human.service.HumanReviewService;
import travelcare_agent.refund.entity.RefundCase;
import travelcare_agent.refund.repository.RefundCaseRepository;
import travelcare_agent.security.CurrentUser;
import travelcare_agent.workflow.entity.Workflow;
import travelcare_agent.workflow.entity.WorkflowStep;
import travelcare_agent.workflow.repository.WorkflowRepository;
import travelcare_agent.workflow.repository.WorkflowStepRepository;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(properties = {"spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.task.scheduling.enabled=false", "travelcare.agent.provider=mock",
        "travelcare.jwt.secret=test-jwt-secret-with-at-least-32-bytes"})
class HumanReviewApprovalGateIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("travelcare_agent").withUsername("test").withPassword("test");

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired HumanReviewService reviews;
    @Autowired SessionService sessions;
    @Autowired SessionRepository sessionRepository;
    @Autowired WorkflowRepository workflows;
    @Autowired WorkflowStepRepository steps;
    @Autowired RefundCaseRepository refunds;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void authenticate() {
        CurrentUser user = new CurrentUser(93001L, "default", Set.of("OPERATOR"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                user, null, java.util.List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))));
    }

    @AfterEach void clearSecurity() { SecurityContextHolder.clearContext(); }

    @Test void ownershipFailureRejectsApprovalBeforeAnyWrite() {
        Fixture fixture = fixture(92001L);
        Snapshot before = snapshot(fixture);

        assertThatThrownBy(() -> reviews.resolveCase(
                fixture.reviewId(), HumanReviewResolution.APPROVED, "unsafe approval"))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getResultCode())
                .isEqualTo(ResultCode.MANUAL_REFUND_VERIFICATION_REQUIRED);

        Snapshot after = snapshot(fixture);
        assertThat(after).isEqualTo(before);
        assertThat(after.workflowStatus()).isEqualTo(WorkflowStatus.NEED_HUMAN.name());
        assertThat(after.reviewStatus()).isEqualTo(HumanReviewCaseStatus.OPEN.name());
        assertThat(after.refundStatus()).isEqualTo(RefundCaseStatus.NEED_HUMAN.name());
    }

    @Test void rejectedResolutionRemainsConsistentAndWritesOneDecisionSet() {
        Fixture fixture = fixture(92002L);
        Snapshot before = snapshot(fixture);

        reviews.resolveCase(fixture.reviewId(), HumanReviewResolution.REJECTED, "ownership rejected");

        Snapshot after = snapshot(fixture);
        assertThat(after.workflowStatus()).isEqualTo(WorkflowStatus.FAILED.name());
        assertThat(after.reviewStatus()).isEqualTo(HumanReviewCaseStatus.RESOLVED.name());
        assertThat(after.refundStatus()).isEqualTo(RefundCaseStatus.INELIGIBLE.name());
        assertThat(after.workflowVersion()).isEqualTo(before.workflowVersion() + 1);
        assertThat(after.reviewVersion()).isEqualTo(before.reviewVersion() + 1);
        assertThat(after.refundVersion()).isEqualTo(before.refundVersion() + 1);
        assertThat(after.auditCount()).isEqualTo(before.auditCount() + 1);
        assertThat(after.eventCount()).isEqualTo(before.eventCount() + 1);
        assertThat(after.outboxCount()).isEqualTo(before.outboxCount());
    }

    private Fixture fixture(long userId) {
        long sessionId = sessions.createSystemSession("default", userId, "WEB").sessionId();
        Workflow workflow = Workflow.create(sessionId, "order_refund_inquiry");
        workflow.setStatus(WorkflowStatus.NEED_HUMAN);
        workflow.setCurrentStep("NEED_HUMAN");
        workflow.setStateJson("{\"reasonCode\":\"order ownership could not be verified\"}");
        workflows.insert(workflow);
        WorkflowStep order = WorkflowStep.start(workflow.getId(), "QUERYING_ORDER", "{}");
        order.succeed("{\"orderId\":\"1001\",\"orderNo\":\"ORD-1001\",\"status\":\"PAID\",\"refundable\":true}");
        steps.save(order);
        RefundCase refund = RefundCase.create(userId, 1001L, workflow.getId(), RefundCaseStatus.NEED_HUMAN,
                BigDecimal.ZERO, "order ownership could not be verified",
                "{\"decision\":\"NEED_HUMAN\",\"checks\":{\"ownership\":\"FAIL\"}}");
        refund.setTenantId("default");
        refunds.insert(refund);
        HumanReviewCase review = reviews.createCase(sessionId, workflow.getId(), refund.getId(),
                "REFUND_REVIEW", "HIGH", "order ownership could not be verified", "{}");
        return new Fixture(workflow.getId(), refund.getId(), review.getId(), sessionId);
    }

    private Snapshot snapshot(Fixture f) {
        return jdbc.queryForObject("""
                SELECT w.status, w.version, h.status, h.version, r.status, r.version,
                  (SELECT COUNT(*) FROM audit_logs WHERE workflow_id=w.id),
                  (SELECT COUNT(*) FROM session_events WHERE session_id=w.session_id),
                  (SELECT COUNT(*) FROM outbox_events WHERE aggregate_id=CAST(h.id AS CHAR))
                FROM workflows w JOIN human_review_cases h ON h.workflow_id=w.id
                JOIN refund_cases r ON r.workflow_id=w.id WHERE w.id=?
                """, (rs, row) -> new Snapshot(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getLong(4),
                rs.getString(5), rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getLong(9)), f.workflowId());
    }

    private record Fixture(long workflowId, long refundId, long reviewId, long sessionId) { }
    private record Snapshot(String workflowStatus, long workflowVersion, String reviewStatus, long reviewVersion,
                            String refundStatus, long refundVersion, long auditCount, long eventCount,
                            long outboxCount) { }
}
