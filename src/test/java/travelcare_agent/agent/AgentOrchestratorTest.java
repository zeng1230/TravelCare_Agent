package travelcare_agent.agent;

import org.junit.jupiter.api.Test;
import travelcare_agent.adapter.order.MockOrderAdapter;
import travelcare_agent.enums.OrderStatus;
import travelcare_agent.enums.WorkflowStatus;
import travelcare_agent.tool.IdempotencyService;
import travelcare_agent.tool.ToolService;
import travelcare_agent.tool.entity.IdempotencyKey;
import travelcare_agent.tool.entity.ToolCall;
import travelcare_agent.tool.repository.IdempotencyKeyRepository;
import travelcare_agent.tool.repository.ToolCallRepository;
import travelcare_agent.workflow.WorkflowEngine;
import travelcare_agent.workflow.WorkflowRegistry;
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

class AgentOrchestratorTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-05-08T08:00:00Z"),
            ZoneId.of("Asia/Shanghai")
    );

    @Test
    void handlesRefundInquiryFromMessageThroughWorkflow() {
        AgentOrchestrator orchestrator = orchestratorWithOrder(new MockOrderAdapter.OrderSnapshot(
                10L,
                "ORD-10",
                1001L,
                OrderStatus.PAID,
                true,
                new BigDecimal("399.00"),
                LocalDateTime.now(CLOCK).plusHours(25)
        ));

        AgentOrchestrator.AgentReply reply = orchestrator.handle(
                new AgentOrchestrator.AgentRequest(101L, 1001L, "Can I refund order ORD-10?")
        );

        assertThat(reply.intent()).isEqualTo("REFUND_INQUIRY");
        assertThat(reply.orderNo()).isEqualTo("ORD-10");
        assertThat(reply.workflowStatus()).isEqualTo(WorkflowStatus.RESPONDED.name());
        assertThat(reply.answer())
                .contains("Order ORD-10 is eligible for refund inquiry")
                .contains("Rule: paid, refundable orders departing after 24 hours can be reviewed.");
    }

    @Test
    void handlesOrderQueryFromMessageThroughWorkflow() {
        AgentOrchestrator orchestrator = orchestratorWithOrder(new MockOrderAdapter.OrderSnapshot(
                11L,
                "ORD-11",
                1001L,
                OrderStatus.PAID,
                true,
                new BigDecimal("288.00"),
                LocalDateTime.now(CLOCK).plusHours(48)
        ));

        AgentOrchestrator.AgentReply reply = orchestrator.handle(
                new AgentOrchestrator.AgentRequest(101L, 1001L, "Please check order ORD-11 status")
        );

        assertThat(reply.intent()).isEqualTo("ORDER_QUERY");
        assertThat(reply.orderNo()).isEqualTo("ORD-11");
        assertThat(reply.answer())
                .contains("Order query recognized for ORD-11")
                .contains("Workflow result:");
    }

    @Test
    void asksForOrderReferenceWhenSlotsAreMissing() {
        AgentOrchestrator orchestrator = orchestratorWithOrder(null);

        AgentOrchestrator.AgentReply reply = orchestrator.handle(
                new AgentOrchestrator.AgentRequest(101L, 1001L, "I want a refund")
        );

        assertThat(reply.intent()).isEqualTo("REFUND_INQUIRY");
        assertThat(reply.orderNo()).isNull();
        assertThat(reply.workflowStatus()).isEqualTo(WorkflowStatus.NEED_HUMAN.name());
        assertThat(reply.answer())
                .contains("Please provide an order reference")
                .contains("Rule: order number is required before refund rules can be checked.");
    }

    private static AgentOrchestrator orchestratorWithOrder(MockOrderAdapter.OrderSnapshot order) {
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

        travelcare_agent.refund.repository.InMemoryRefundCaseRepository refundRepo = new travelcare_agent.refund.repository.InMemoryRefundCaseRepository();
        travelcare_agent.human.repository.InMemoryHumanReviewCaseRepository hrRepo = new travelcare_agent.human.repository.InMemoryHumanReviewCaseRepository();
        travelcare_agent.audit.AuditService auditService = org.mockito.Mockito.mock(travelcare_agent.audit.AuditService.class);
        travelcare_agent.conversation.service.SessionEventService eventService = org.mockito.Mockito.mock(travelcare_agent.conversation.service.SessionEventService.class);
        travelcare_agent.workflow.repository.InMemoryWorkflowRepository workflowRepo = new travelcare_agent.workflow.repository.InMemoryWorkflowRepository();

        travelcare_agent.human.service.HumanReviewService humanReviewService = new travelcare_agent.human.service.HumanReviewService(
                hrRepo, eventService, auditService, workflowRepo, refundRepo
        );

        WorkflowEngine engine = new WorkflowEngine(
                workflowRepo,
                new InMemoryWorkflowStepRepository(),
                new WorkflowRegistry(List.of(new OrderRefundInquiryWorkflow(
                        new MockOrderAdapter((orderId, orderNo, userId) -> Optional.ofNullable(order)
                                .filter(snapshot -> orderId == null || snapshot.orderId().equals(orderId))
                                .filter(snapshot -> orderNo == null || snapshot.orderNo().equals(orderNo))
                                .filter(snapshot -> snapshot.userId().equals(userId))),
                        toolService,
                        CLOCK,
                        new travelcare_agent.policy.RefundEligibilityPolicy(CLOCK),
                        refundRepo,
                        auditService
                )))
        );

        travelcare_agent.agent.ContextAssembler contextAssembler = org.mockito.Mockito.mock(travelcare_agent.agent.ContextAssembler.class);
        travelcare_agent.agent.AgentContext dummyContext = new travelcare_agent.agent.AgentContext(
                List.of(),
                null,
                null,
                List.of(),
                List.of()
        );
        org.mockito.Mockito.when(contextAssembler.assemble(org.mockito.Mockito.anyLong(), org.mockito.Mockito.anyString()))
                .thenReturn(dummyContext);

        return new AgentOrchestrator(
                new MockIntentClassifier(),
                new MockResponseGenerator(),
                engine,
                humanReviewService,
                refundRepo,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                contextAssembler
        );
    }
}

