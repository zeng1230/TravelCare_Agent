package travelcare_agent.workflow;

import travelcare_agent.adapter.order.OrderSnapshot;

import org.junit.jupiter.api.Test;
import travelcare_agent.audit.AuditService;
import travelcare_agent.enums.OrderStatus;
import travelcare_agent.enums.RefundCaseStatus;
import travelcare_agent.enums.WorkflowStatus;
import travelcare_agent.enums.WorkflowStepStatus;
import travelcare_agent.adapter.order.MockOrderAdapter;
import travelcare_agent.audit.entity.AuditLog;
import travelcare_agent.audit.repository.InMemoryAuditLogRepository;
import travelcare_agent.policy.RefundEligibilityPolicy;
import travelcare_agent.refund.entity.RefundCase;
import travelcare_agent.refund.repository.InMemoryRefundCaseRepository;
import travelcare_agent.tool.IdempotencyService;
import travelcare_agent.tool.ToolService;
import travelcare_agent.tool.entity.IdempotencyKey;
import travelcare_agent.tool.entity.ToolCall;
import travelcare_agent.tool.repository.IdempotencyKeyRepository;
import travelcare_agent.tool.repository.ToolCallRepository;
import travelcare_agent.workflow.entity.Workflow;
import travelcare_agent.workflow.entity.WorkflowStep;
import travelcare_agent.workflow.repository.InMemoryWorkflowRepository;
import travelcare_agent.workflow.repository.InMemoryWorkflowStepRepository;
import travelcare_agent.workflow.workflows.OrderRefundInquiryWorkflow;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class OrderRefundInquiryWorkflowTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-05-08T08:00:00Z"),
            ZoneId.of("Asia/Shanghai")
    );

    @Test
    void respondsWhenPaidRefundableOrderIsMoreThanTwentyFourHoursAway() {
        Fixture fixture = Fixture.withOrder(new OrderSnapshot(
                10L,
                "ORD-10",
                1001L,
                OrderStatus.PAID,
                true,
                new BigDecimal("399.00"),
                LocalDateTime.now(CLOCK).plusHours(25)
        ));

        WorkflowEngine.WorkflowResult result = fixture.engine.start(
                "order_refund_inquiry",
                new WorkflowEngine.WorkflowCommand(101L, 1001L, 10L, null, "can I refund this order?")
        );

        assertThat(result.workflow().getStatus()).isEqualTo(WorkflowStatus.RESPONDED);
        assertThat(result.workflow().getCurrentStep()).isEqualTo("RESPONDED");
        assertThat(result.answer()).contains("eligible for refund inquiry");
        assertThat(fixture.refundCaseRepository.findAll())
                .extracting(RefundCase::getStatus, RefundCase::getOrderId, RefundCase::getWorkflowId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        RefundCaseStatus.ELIGIBLE,
                        10L,
                        result.workflow().getId()
                ));
        assertThat(fixture.auditLogRepository.findAll())
                .extracting(AuditLog::getAction, AuditLog::getTargetType, AuditLog::getTargetId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("ORDER_QUERY", "ORDER", 10L),
                        org.assertj.core.groups.Tuple.tuple("REFUND_RULE_CHECK", "REFUND_CASE", fixture.refundCaseRepository.findAll().get(0).getId())
                );
        assertThat(fixture.auditLogRepository.findAll())
                .allSatisfy(auditLog -> {
                    assertThat(auditLog.getSessionId()).isEqualTo(101L);
                    assertThat(auditLog.getWorkflowId()).isEqualTo(result.workflow().getId());
                    assertThat(auditLog.getTargetId()).isNotNull();
                    assertThat(auditLog.getEvidenceJson()).isNotBlank();
                });
        assertThat(fixture.auditLogRepository.findAll().get(0).getEvidenceJson())
                .contains("\"orderId\":\"10\"")
                .contains("\"orderNo\":\"ORD-10\"");
        assertThat(fixture.auditLogRepository.findAll().get(1).getEvidenceJson())
                .contains("\"decision\":\"ELIGIBLE\"")
                .contains("\"orderId\":\"10\"");
        assertThat(fixture.stepRepository.findByWorkflowId(result.workflow().getId()))
                .extracting(WorkflowStep::getStepName, WorkflowStep::getStatus)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("COLLECTING_ORDER_REFERENCE", WorkflowStepStatus.SUCCESS),
                        org.assertj.core.groups.Tuple.tuple("QUERYING_ORDER", WorkflowStepStatus.SUCCESS),
                        org.assertj.core.groups.Tuple.tuple("CHECKING_REFUND_RULES", WorkflowStepStatus.SUCCESS),
                        org.assertj.core.groups.Tuple.tuple("RESPONDED", WorkflowStepStatus.SUCCESS)
                );
    }

    @Test
    void needsHumanWhenOrderReferenceIsMissing() {
        Fixture fixture = Fixture.withOrder(null);

        WorkflowEngine.WorkflowResult result = fixture.engine.start(
                "order_refund_inquiry",
                new WorkflowEngine.WorkflowCommand(101L, 1001L, null, null, "I want a refund")
        );

        assertThat(result.workflow().getStatus()).isEqualTo(WorkflowStatus.NEED_HUMAN);
        assertThat(result.workflow().getCurrentStep()).isEqualTo("NEED_HUMAN");
        assertThat(result.answer()).contains("order reference");
        assertThat(fixture.refundCaseRepository.findAll()).isEmpty();
        assertThat(fixture.auditLogRepository.findAll())
                .extracting(AuditLog::getAction, AuditLog::getTargetType)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("HANDOFF_REQUIRED", "WORKFLOW"));
        assertThat(fixture.stepRepository.findByWorkflowId(result.workflow().getId()))
                .extracting(WorkflowStep::getStepName, WorkflowStep::getStatus, WorkflowStep::getErrorCode)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "COLLECTING_ORDER_REFERENCE",
                                WorkflowStepStatus.FAILED,
                                "ORDER_REFERENCE_MISSING"
                        ),
                        org.assertj.core.groups.Tuple.tuple("NEED_HUMAN", WorkflowStepStatus.SUCCESS, null)
                );
    }

    @Test
    void needsHumanWhenOrderIsNotFound() {
        Fixture fixture = Fixture.withOrder(null);

        WorkflowEngine.WorkflowResult result = fixture.engine.start(
                "order_refund_inquiry",
                new WorkflowEngine.WorkflowCommand(101L, 1001L, 404L, null, "can I refund order 404?")
        );

        assertThat(result.workflow().getStatus()).isEqualTo(WorkflowStatus.NEED_HUMAN);
        assertThat(result.answer()).contains("could not find");
        assertThat(fixture.refundCaseRepository.findAll()).isEmpty();
        assertThat(fixture.auditLogRepository.findAll())
                .extracting(AuditLog::getAction, AuditLog::getTargetType)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("HANDOFF_REQUIRED", "WORKFLOW"));
        assertThat(fixture.stepRepository.findByWorkflowId(result.workflow().getId()))
                .extracting(WorkflowStep::getStepName, WorkflowStep::getStatus, WorkflowStep::getErrorCode)
                .contains(
                        org.assertj.core.groups.Tuple.tuple("QUERYING_ORDER", WorkflowStepStatus.FAILED, "ORDER_NOT_FOUND")
                );
    }

    @Test
    void respondsIneligibleWhenOrderAlreadyUsed() {
        Fixture fixture = Fixture.withOrder(new OrderSnapshot(
                10L,
                "ORD-10",
                1001L,
                OrderStatus.USED,
                true,
                new BigDecimal("399.00"),
                LocalDateTime.now(CLOCK).plusHours(25)
        ));

        WorkflowEngine.WorkflowResult result = fixture.engine.start(
                "order_refund_inquiry",
                new WorkflowEngine.WorkflowCommand(101L, 1001L, 10L, null, "can I refund this order?")
        );

        assertThat(result.workflow().getStatus()).isEqualTo(WorkflowStatus.RESPONDED);
        assertThat(result.answer()).contains("not eligible").contains("order status is USED");
        assertThat(fixture.refundCaseRepository.findAll())
                .extracting(RefundCase::getStatus, RefundCase::getReason)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(RefundCaseStatus.INELIGIBLE, "order status is USED"));
        assertThat(fixture.auditLogRepository.findAll().get(1).getEvidenceJson())
                .contains("\"decision\":\"INELIGIBLE\"")
                .contains("\"orderStatus\":\"FAIL\"");
        assertThat(fixture.stepRepository.findByWorkflowId(result.workflow().getId()))
                .extracting(WorkflowStep::getStepName, WorkflowStep::getStatus)
                .contains(org.assertj.core.groups.Tuple.tuple("CHECKING_REFUND_RULES", WorkflowStepStatus.SUCCESS));
    }

    @Test
    void needsHumanAndAuditsWhenOrderBelongsToAnotherUser() {
        Fixture fixture = Fixture.withUnfilteredOrder(new OrderSnapshot(
                10L,
                "ORD-10",
                2002L,
                OrderStatus.PAID,
                true,
                new BigDecimal("399.00"),
                LocalDateTime.now(CLOCK).plusHours(25)
        ));

        WorkflowEngine.WorkflowResult result = fixture.engine.start(
                "order_refund_inquiry",
                new WorkflowEngine.WorkflowCommand(101L, 1001L, 10L, null, "can I refund this order?")
        );

        assertThat(result.workflow().getStatus()).isEqualTo(WorkflowStatus.NEED_HUMAN);
        assertThat(result.answer()).contains("manual support");
        assertThat(fixture.refundCaseRepository.findAll())
                .extracting(RefundCase::getStatus, RefundCase::getReason)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        RefundCaseStatus.NEED_HUMAN,
                        "order ownership could not be verified"
                ));
        assertThat(fixture.auditLogRepository.findAll())
                .extracting(AuditLog::getAction)
                .containsExactly("ORDER_QUERY", "REFUND_RULE_CHECK", "HANDOFF_REQUIRED");
    }

    @Test
    void needsHumanWhenOrderLookupThrows() {
        Fixture fixture = Fixture.withFailingLookup();

        WorkflowEngine.WorkflowResult result = fixture.engine.start(
                "order_refund_inquiry",
                new WorkflowEngine.WorkflowCommand(101L, 1001L, 10L, null, "can I refund this order?")
        );

        assertThat(result.workflow().getStatus()).isEqualTo(WorkflowStatus.NEED_HUMAN);
        assertThat(result.answer()).contains("manual support");
        assertThat(fixture.auditLogRepository.findAll())
                .extracting(AuditLog::getAction, AuditLog::getTargetType)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("HANDOFF_REQUIRED", "WORKFLOW"));
        assertThat(fixture.stepRepository.findByWorkflowId(result.workflow().getId()))
                .extracting(WorkflowStep::getStepName, WorkflowStep::getStatus, WorkflowStep::getErrorCode)
                .contains(org.assertj.core.groups.Tuple.tuple("QUERYING_ORDER", WorkflowStepStatus.FAILED, "ORDER_LOOKUP_FAILED"));
    }

    private static class Fixture {
        private final InMemoryWorkflowStepRepository stepRepository;
        private final InMemoryRefundCaseRepository refundCaseRepository;
        private final InMemoryAuditLogRepository auditLogRepository;
        private final WorkflowEngine engine;

        private Fixture(
                InMemoryWorkflowStepRepository stepRepository,
                InMemoryRefundCaseRepository refundCaseRepository,
                InMemoryAuditLogRepository auditLogRepository,
                WorkflowEngine engine
        ) {
            this.stepRepository = stepRepository;
            this.refundCaseRepository = refundCaseRepository;
            this.auditLogRepository = auditLogRepository;
            this.engine = engine;
        }

        static Fixture withOrder(OrderSnapshot order) {
            return create((orderId, orderNo, userId) -> Optional.ofNullable(order)
                    .filter(snapshot -> orderId == null || snapshot.orderId().equals(orderId))
                    .filter(snapshot -> orderNo == null || snapshot.orderNo().equals(orderNo))
                    .filter(snapshot -> snapshot.userId().equals(userId)));
        }

        static Fixture withUnfilteredOrder(OrderSnapshot order) {
            return create((orderId, orderNo, userId) -> Optional.ofNullable(order)
                    .filter(snapshot -> orderId == null || snapshot.orderId().equals(orderId))
                    .filter(snapshot -> orderNo == null || snapshot.orderNo().equals(orderNo)));
        }

        static Fixture withFailingLookup() {
            return create((orderId, orderNo, userId) -> {
                throw new IllegalStateException("adapter timeout");
            });
        }

        private static Fixture create(MockOrderAdapter.OrderLookup orderLookup) {
            InMemoryWorkflowRepository workflowRepository = new InMemoryWorkflowRepository();
            InMemoryWorkflowStepRepository stepRepository = new InMemoryWorkflowStepRepository();
            InMemoryRefundCaseRepository refundCaseRepository = new InMemoryRefundCaseRepository();
            InMemoryAuditLogRepository auditLogRepository = new InMemoryAuditLogRepository();
            
            ToolCallRepository toolCallRepository = new ToolCallRepository() {
                public ToolCall save(ToolCall call) {
                    if (call.getId() == null) call.setId(new java.util.Random().nextLong());
                    return call;
                }
                public Optional<ToolCall> findById(Long id) { return Optional.empty(); }
                public Optional<ToolCall> findByIdempotencyKey(String key) { return Optional.empty(); }
            };
            IdempotencyKeyRepository keyRepo = new IdempotencyKeyRepository() {
                private final java.util.Map<String, IdempotencyKey> store = new java.util.concurrent.ConcurrentHashMap<>();
                public IdempotencyKey save(IdempotencyKey key) { store.put(key.getIdempotencyKey(), key); return key; }
                public Optional<IdempotencyKey> findByKey(String key) { return Optional.ofNullable(store.get(key)); }
            };
            ToolService toolService = new ToolService(toolCallRepository, new IdempotencyService(keyRepo));

            WorkflowRegistry registry = new WorkflowRegistry(List.of(
                    new OrderRefundInquiryWorkflow(
                            new MockOrderAdapter(orderLookup),
                            toolService,
                            CLOCK,
                            new RefundEligibilityPolicy(CLOCK),
                            refundCaseRepository,
                            new AuditService(auditLogRepository)
                    )
            ));
            return new Fixture(
                    stepRepository,
                    refundCaseRepository,
                    auditLogRepository,
                    new WorkflowEngine(workflowRepository, stepRepository, registry)
            );
        }
    }
}
