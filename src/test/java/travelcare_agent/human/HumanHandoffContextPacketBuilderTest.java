package travelcare_agent.human;

import org.junit.jupiter.api.Test;
import travelcare_agent.conversation.entity.SessionEvent;
import travelcare_agent.conversation.repository.InMemorySessionEventRepository;
import travelcare_agent.enums.RefundCaseStatus;
import travelcare_agent.enums.SessionEventRole;
import travelcare_agent.enums.SessionEventType;
import travelcare_agent.enums.WorkflowStatus;
import travelcare_agent.human.packet.HumanHandoffContextPacket;
import travelcare_agent.human.packet.HumanHandoffContextPacketBuilder;
import travelcare_agent.refund.entity.RefundCase;
import travelcare_agent.refund.repository.InMemoryRefundCaseRepository;
import travelcare_agent.trace.RedactionService;
import travelcare_agent.trace.TraceQueryService;
import travelcare_agent.trace.TraceSnapshotType;
import travelcare_agent.trace.entity.TraceRun;
import travelcare_agent.trace.entity.TraceSnapshot;
import travelcare_agent.trace.entity.TraceSpan;
import travelcare_agent.trace.repository.TraceRunRepository;
import travelcare_agent.workflow.entity.Workflow;
import travelcare_agent.workflow.entity.WorkflowStep;
import travelcare_agent.workflow.repository.InMemoryWorkflowRepository;
import travelcare_agent.workflow.repository.InMemoryWorkflowStepRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HumanHandoffContextPacketBuilderTest {

    @Test
    void buildsPacketFromPersistedWorkflowRefundTraceAndConversationFacts() {
        InMemorySessionEventRepository events = new InMemorySessionEventRepository();
        events.save(event(10L, 1, SessionEventRole.USER, "Can I refund order ORD-1001? phone=13812345678"));
        events.save(event(10L, 2, SessionEventRole.ASSISTANT, "manual support will verify this refund inquiry."));

        InMemoryWorkflowRepository workflows = new InMemoryWorkflowRepository();
        Workflow workflow = Workflow.create(10L, "order_refund_inquiry");
        workflow.setId(20L);
        workflow.transitionTo(WorkflowStatus.NEED_HUMAN, "NEED_HUMAN",
                "{\"reasonCode\":\"order ownership could not be verified\"}");
        workflows.save(workflow);

        InMemoryWorkflowStepRepository steps = new InMemoryWorkflowStepRepository();
        WorkflowStep query = WorkflowStep.start(20L, "QUERYING_ORDER", "{\"orderNo\":\"ORD-1001\"}");
        query.setId(2001L);
        query.succeed("{\"orderId\":\"1001\",\"orderNo\":\"ORD-1001\",\"status\":\"PAID\",\"refundable\":true}");
        steps.save(query);

        InMemoryRefundCaseRepository refunds = new InMemoryRefundCaseRepository();
        RefundCase refund = RefundCase.create(1001L, 1001L, 20L, RefundCaseStatus.NEED_HUMAN,
                new BigDecimal("188.00"), "order ownership could not be verified",
                "{\"decision\":\"NEED_HUMAN\",\"checks\":{\"ownership\":\"FAIL\"},\"apiKey\":\"sk-private\"}");
        refund.setId(30L);
        refunds.save(refund);

        TraceRun run = traceRun();
        TraceRunRepository traceRuns = mock(TraceRunRepository.class);
        when(traceRuns.findLatestBySessionIdAndWorkflowId(10L, 20L)).thenReturn(Optional.of(run));
        TraceQueryService traceQueryService = mock(TraceQueryService.class);
        when(traceQueryService.get("trace-1")).thenReturn(new TraceQueryService.TraceDetail(
                run,
                List.of(),
                List.of(),
                List.of(
                        snapshot(TraceSnapshotType.CITATION_SUMMARY.name(), """
                                {"citations":[{"retrievalRunId":"ret-1","documentId":7,"chunkId":8,
                                "title":"Refund SOP","sourceUri":"https://example.com/sop?token=secret&ok=1"}],
                                "rejectedCitationCandidates":[{"retrievalRunId":"ret-1","documentId":9,"chunkId":10,
                                "title":"Old SOP","sourceUri":"https://example.com/old","reasonCode":"EXPIRED_SOURCE"}]}
                                """),
                        snapshot(TraceSnapshotType.MODEL_SAFETY_DECISION.name(), """
                                {"safetyDecision":"HANDOFF","reasonCode":"AUTHORITATIVE_DECISION_CONFLICT",
                                "riskFlags":["REFUND_CONFLICT"]}
                                """)
                )));
        when(traceQueryService.diagnostics("trace-1")).thenReturn(new TraceQueryService.TraceDiagnostics(
                "trace-1", "SUCCEEDED", "mock", "mock-stage10a", "stage10a-default",
                List.of(), List.of(), List.of(toolSpan("SUPPLIER_TIMEOUT")), List.of(), List.of(), null,
                new TraceQueryService.RedactionSummary(0), List.of(), false, null, List.of(), List.of()));

        HumanHandoffContextPacketBuilder builder = new HumanHandoffContextPacketBuilder(
                events, workflows, steps, refunds, traceRuns, traceQueryService, new RedactionService());

        HumanHandoffContextPacket packet = builder.build(new HumanHandoffContextPacketBuilder.Request(
                10L, 20L, 30L, "REFUND_REVIEW", "HIGH",
                "order ownership could not be verified", "{\"reasonCode\":\"order ownership could not be verified\"}"));

        assertThat(packet.packetVersion()).isEqualTo("PR-3A-v1");
        assertThat(packet.packetMode()).isEqualTo("MATERIALIZED");
        assertThat(packet.customerGoal().summary())
                .isEqualTo("Customer wants to check whether order ORD-1001 can be refunded.");
        assertThat(packet.customerGoal().latestUserMessage()).contains("[REDACTED]");
        assertThat(packet.refundRuleDecision().policyResultJson()).doesNotContain("sk-private");
        assertThat(packet.verifiedOrderFacts().orderNo()).isEqualTo("ORD-1001");
        assertThat(packet.refundRuleDecision().status()).isEqualTo("NEED_HUMAN");
        assertThat(packet.ragEvidence().acceptedCitations()).singleElement()
                .satisfies(citation -> assertThat(citation.sourceUri()).isEqualTo("https://example.com/sop?ok=1"));
        assertThat(packet.ragEvidence().rejectedCitations()).singleElement()
                .satisfies(citation -> assertThat(citation.rejectionReason()).isEqualTo("EXPIRED_SOURCE"));
        assertThat(packet.toolCalls()).singleElement()
                .satisfies(tool -> assertThat(tool.errorCode()).isEqualTo("SUPPLIER_TIMEOUT"));
        assertThat(packet.supplierGateway().failed()).isTrue();
        assertThat(packet.safetyDecision().decision()).isEqualTo("HANDOFF");
        assertThat(packet.recommendedNextSteps().steps())
                .extracting(HumanHandoffContextPacket.RecommendedStep::action)
                .contains("VERIFY_ORDER_OWNERSHIP", "CHECK_SUPPLIER_STATUS");
    }

    @Test
    void legacyFallbackPacketKeepsIdsReasonAndDurableContextWithoutLiveReconstruction() {
        InMemorySessionEventRepository events = new InMemorySessionEventRepository();
        events.save(event(10L, 1, SessionEventRole.USER, "I need refund help"));

        InMemoryWorkflowRepository workflows = new InMemoryWorkflowRepository();
        Workflow workflow = Workflow.create(10L, "order_refund_inquiry");
        workflow.setId(20L);
        workflow.transitionTo(WorkflowStatus.NEED_HUMAN, "NEED_HUMAN", "{\"reasonCode\":\"ORDER_LOOKUP_FAILED\"}");
        workflows.save(workflow);

        InMemoryRefundCaseRepository refunds = new InMemoryRefundCaseRepository();
        RefundCase refund = RefundCase.create(1001L, 1001L, 20L, RefundCaseStatus.NEED_HUMAN,
                null, "ORDER_LOOKUP_FAILED", "{\"decision\":\"NEED_HUMAN\"}");
        refund.setId(30L);
        refunds.save(refund);

        HumanHandoffContextPacketBuilder builder = new HumanHandoffContextPacketBuilder(
                events, workflows, new InMemoryWorkflowStepRepository(), refunds,
                emptyTraceRuns(), mock(TraceQueryService.class), new RedactionService());

        HumanHandoffContextPacket packet = builder.fromStoredEvidence(caseWithEvidence(
                99L, 10L, 20L, 30L, "ORDER_LOOKUP_FAILED", "{\"reasonCode\":\"ORDER_LOOKUP_FAILED\"}"));

        assertThat(packet.packetMode()).isEqualTo("LEGACY_FALLBACK");
        assertThat(packet.caseId()).isEqualTo(99L);
        assertThat(packet.customerGoal().latestUserMessage()).isEqualTo("I need refund help");
        assertThat(packet.refundRuleDecision().status()).isEqualTo("NEED_HUMAN");
        assertThat(packet.handoffReason().reasonCode()).isEqualTo("ORDER_LOOKUP_FAILED");
        assertThat(packet.warnings()).contains("LEGACY_EVIDENCE_JSON");
        assertThat(packet.recommendedNextSteps().steps())
                .extracting(HumanHandoffContextPacket.RecommendedStep::action)
                .contains("CHECK_SUPPLIER_STATUS");
    }

    private static SessionEvent event(Long sessionId, int seqNo, SessionEventRole role, String content) {
        return SessionEvent.create(sessionId, seqNo, SessionEventType.MESSAGE, role, content, "{}");
    }

    private static TraceRun traceRun() {
        TraceRun run = new TraceRun();
        run.setTraceId("trace-1");
        run.setSessionId(10L);
        run.setWorkflowId(20L);
        return run;
    }

    private static TraceSnapshot snapshot(String type, String payload) {
        TraceSnapshot snapshot = new TraceSnapshot();
        snapshot.setSnapshotType(type);
        snapshot.setPayloadJson(payload);
        return snapshot;
    }

    private static TraceSpan toolSpan(String errorCode) {
        TraceSpan span = new TraceSpan();
        span.setSpanType("TOOL");
        span.setName("GetOrderTool");
        span.setStatus("FAILED");
        span.setErrorCode(errorCode);
        span.setOutputRef("TOOL_CALL:501");
        return span;
    }

    private static TraceRunRepository emptyTraceRuns() {
        TraceRunRepository repository = mock(TraceRunRepository.class);
        when(repository.findLatestBySessionIdAndWorkflowId(10L, 20L)).thenReturn(Optional.empty());
        return repository;
    }

    private static travelcare_agent.human.entity.HumanReviewCase caseWithEvidence(
            Long caseId, Long sessionId, Long workflowId, Long refundCaseId, String reasonCode, String evidenceJson) {
        travelcare_agent.human.entity.HumanReviewCase hrCase = new travelcare_agent.human.entity.HumanReviewCase();
        hrCase.setId(caseId);
        hrCase.setSessionId(sessionId);
        hrCase.setWorkflowId(workflowId);
        hrCase.setRefundCaseId(refundCaseId);
        hrCase.setReasonCode(reasonCode);
        hrCase.setEvidenceJson(evidenceJson);
        return hrCase;
    }
}
