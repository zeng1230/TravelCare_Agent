package travelcare_agent.agent;

import org.junit.jupiter.api.Test;
import travelcare_agent.answerability.AnswerabilityDecision;
import travelcare_agent.answerability.AnswerabilityReasonCode;
import travelcare_agent.answerability.AnswerabilityRequiredAction;
import travelcare_agent.answerability.AnswerabilityStatus;
import travelcare_agent.answerability.CitationPolicy;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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

    @Test
    void unanswerableFallbackReplyDoesNotCallModelForKnowledgeAnswer() {
        AgentModelService modelService = org.mockito.Mockito.mock(AgentModelService.class);
        AgentOrchestrator orchestrator = orchestratorWithOrderAndContext(
                new MockOrderAdapter.OrderSnapshot(
                        12L,
                        "ORD-12",
                        1001L,
                        OrderStatus.PAID,
                        true,
                        new BigDecimal("188.00"),
                        LocalDateTime.now(CLOCK).plusHours(48)
                ),
                new AgentContext(
                        List.of(),
                        null,
                        null,
                        List.of(),
                        List.of(),
                        new AnswerabilityDecision(
                                AnswerabilityStatus.UNANSWERABLE,
                                AnswerabilityReasonCode.NO_RETRIEVAL,
                                AnswerabilityRequiredAction.FALLBACK_REPLY,
                                CitationPolicy.OPTIONAL,
                                List.of(),
                                false,
                                false,
                                false,
                                List.of(),
                                List.of(),
                                "I don't have enough verified knowledge to answer that from the knowledge base. Manual support can help verify it."
                        )
                ),
                modelService
        );
        org.mockito.Mockito.when(modelService.classifyIntentAndExtractSlots(
                org.mockito.Mockito.anyLong(), org.mockito.Mockito.isNull(), org.mockito.Mockito.anyList(),
                org.mockito.Mockito.anyList(), org.mockito.Mockito.anyString()
        )).thenReturn(new MockIntentClassifier.IntentResult("FAQ", "ORD-12"));

        AgentOrchestrator.AgentReply reply = orchestrator.handle(
                new AgentOrchestrator.AgentRequest(101L, 1001L, "What policy supports refund order ORD-12?")
        );

        assertThat(reply.answer()).contains("I don't have enough verified knowledge");
        assertThat(reply.answerabilityStatus()).isEqualTo("UNANSWERABLE");
        verify(modelService, never()).generateCustomerAnswer(
                org.mockito.Mockito.anyLong(), org.mockito.Mockito.anyLong(), org.mockito.Mockito.anyList(),
                org.mockito.Mockito.anyList(), org.mockito.Mockito.anyString()
        );
    }

    @Test
    void locksAnswerabilityMetadataAfterRefundPolicyDecision() {
        AgentOrchestrator orchestrator = orchestratorWithOrderAndContext(
                new MockOrderAdapter.OrderSnapshot(
                        13L,
                        "ORD-13",
                        1001L,
                        OrderStatus.PAID,
                        true,
                        new BigDecimal("128.00"),
                        LocalDateTime.now(CLOCK).plusHours(48)
                ),
                new AgentContext(
                        List.of(),
                        null,
                        null,
                        List.of(),
                        List.of(),
                        new AnswerabilityDecision(
                                AnswerabilityStatus.ANSWERABLE,
                                AnswerabilityReasonCode.SUFFICIENT_CONTEXT,
                                AnswerabilityRequiredAction.ALLOW_MODEL,
                                CitationPolicy.REQUIRED,
                                List.of(101L),
                                false,
                                false,
                                false,
                                List.of(),
                                List.of(),
                                null
                        )
                ),
                null
        );

        AgentOrchestrator.AgentReply reply = orchestrator.handle(
                new AgentOrchestrator.AgentRequest(101L, 1001L, "refund ORD-13")
        );

        assertThat(reply.businessDecisionLocked()).isTrue();
        assertThat(reply.ragMayOverrideBusinessDecision()).isFalse();
    }

    private static AgentOrchestrator orchestratorWithOrder(MockOrderAdapter.OrderSnapshot order) {
        return orchestratorWithOrderAndContext(order, new travelcare_agent.agent.AgentContext(
                List.of(),
                null,
                null,
                List.of(),
                List.of()
        ), null);
    }

    private static AgentOrchestrator orchestratorWithOrderAndContext(MockOrderAdapter.OrderSnapshot order,
            AgentContext agentContext, AgentModelService agentModelService) {
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
        org.mockito.Mockito.when(contextAssembler.assemble(org.mockito.Mockito.anyLong(), org.mockito.Mockito.anyString()))
                .thenReturn(agentContext);

        return new AgentOrchestrator(
                new MockIntentClassifier(),
                new MockResponseGenerator(),
                engine,
                humanReviewService,
                refundRepo,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                contextAssembler,
                agentModelService
        );
    }
}
