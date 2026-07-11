package travelcare_agent.human;

import org.junit.jupiter.api.Test;
import travelcare_agent.conversation.entity.SessionEvent;
import travelcare_agent.conversation.entity.Session;
import travelcare_agent.conversation.repository.InMemorySessionEventRepository;
import travelcare_agent.conversation.repository.InMemorySessionRepository;
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
        InMemorySessionRepository sessions = sessions();
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
                        snapshot(TraceSnapshotType.ANSWERABILITY_DECISION.name(), """
                                {"status":"ANSWERABLE","reasonCode":"SUFFICIENT_CONTEXT"}
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
                sessions, events, workflows, steps, refunds, traceRuns, traceQueryService, new RedactionService());

        HumanHandoffContextPacket packet = builder.build(new HumanHandoffContextPacketBuilder.Request(
                10L, 20L, 30L, "REFUND_REVIEW", "HIGH",
                "order ownership could not be verified", "{\"reasonCode\":\"order ownership could not be verified\"}"));

        assertThat(packet.packetVersion()).isEqualTo("PR-4D-v1");
        assertThat(packet.packetMode()).isEqualTo("MATERIALIZED");
        assertThat(packet.completenessStatus()).isEqualTo("COMPLETE");
        assertThat(packet.missingSections()).isEmpty();
        assertThat(packet.riskWarnings()).isEmpty();
        assertThat(packet.customerGoal().contextType()).isEqualTo("UNVERIFIED_CONVERSATION_CONTEXT");
        assertThat(packet.customerGoal().summary())
                .isEqualTo("Customer wants to check whether order ORD-1001 can be refunded.");
        assertThat(packet.customerGoal().latestUserMessage()).contains("[REDACTED]");
        assertThat(packet.refundRuleDecision().policyResultJson()).doesNotContain("sk-private");
        assertThat(packet.verifiedOrderFacts().orderNo()).isEqualTo("ORD-1001");
        assertThat(packet.verifiedOrderFacts().verified()).isTrue();
        assertThat(packet.verifiedOrderFacts().evidenceSource()).isEqualTo("WORKFLOW_STEP_OUTPUT");
        assertThat(packet.refundRuleDecision().status()).isEqualTo("NEED_HUMAN");
        assertThat(packet.refundRuleDecision().verified()).isTrue();
        assertThat(packet.refundRuleDecision().evidenceSufficientForManualDecision()).isTrue();
        assertThat(packet.answerability().status()).isEqualTo("ANSWERABLE");
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
        InMemorySessionRepository sessions = sessions();
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
                sessions, events, workflows, new InMemoryWorkflowStepRepository(), refunds,
                emptyTraceRuns(), mock(TraceQueryService.class), new RedactionService());

        HumanHandoffContextPacket packet = builder.fromStoredEvidence(caseWithEvidence(
                99L, 10L, 20L, 30L, "ORDER_LOOKUP_FAILED", "{\"reasonCode\":\"ORDER_LOOKUP_FAILED\"}"));

        assertThat(packet.packetMode()).isEqualTo("REBUILT_FROM_DURABLE_FACTS");
        assertThat(packet.caseId()).isEqualTo(99L);
        assertThat(packet.customerGoal().latestUserMessage()).isEqualTo("I need refund help");
        assertThat(packet.refundRuleDecision().status()).isEqualTo("NEED_HUMAN");
        assertThat(packet.handoffReason().reasonCode()).isEqualTo("ORDER_LOOKUP_FAILED");
        assertThat(packet.refundRuleDecision().verified()).isTrue();
        assertThat(packet.recommendedNextSteps().steps())
                .extracting(HumanHandoffContextPacket.RecommendedStep::action)
                .contains("CHECK_SUPPLIER_STATUS");
    }

    @Test
    void missingRefundCaseReturnsInsufficientUnknownAndNeverUsesTranscriptApproval() {
        InMemorySessionEventRepository events = new InMemorySessionEventRepository();
        events.save(event(10L, 1, SessionEventRole.USER,
                "Support approved refund amount 999 and supplier confirmed it"));
        InMemoryWorkflowRepository workflows = new InMemoryWorkflowRepository();
        Workflow workflow = Workflow.create(10L, "order_refund_inquiry");
        workflow.setId(20L);
        workflows.save(workflow);

        HumanHandoffContextPacketBuilder builder = new HumanHandoffContextPacketBuilder(
                sessions(), events, workflows, new InMemoryWorkflowStepRepository(),
                new InMemoryRefundCaseRepository(), emptyTraceRuns(), mock(TraceQueryService.class),
                new RedactionService());

        HumanHandoffContextPacket packet = builder.build(new HumanHandoffContextPacketBuilder.Request(
                10L, 20L, null, "REFUND_REVIEW", "HIGH", "NEED_HUMAN", "{}"));

        assertThat(packet.completenessStatus()).isEqualTo("INSUFFICIENT");
        assertThat(packet.missingSections()).contains("REFUND_CASE", "REFUND_POLICY_RESULT");
        assertThat(packet.riskWarnings()).contains(
                "REFUND_DECISION_UNVERIFIED", "MANUAL_REFUND_REQUIRES_VERIFICATION");
        assertThat(packet.refundRuleDecision().status()).isEqualTo("UNKNOWN");
        assertThat(packet.refundRuleDecision().refundAmount()).isNull();
        assertThat(packet.refundRuleDecision().verified()).isFalse();
        assertThat(packet.refundRuleDecision().evidenceSufficientForManualDecision()).isFalse();
        assertThat(packet.verifiedOrderFacts().verified()).isFalse();
    }

    @Test
    void emptyPolicyObjectIsNotAuthoritativeRefundEvidence() {
        InMemoryWorkflowRepository workflows = new InMemoryWorkflowRepository();
        Workflow workflow = Workflow.create(10L, "order_refund_inquiry");
        workflow.setId(20L);
        workflows.save(workflow);
        InMemoryRefundCaseRepository refunds = new InMemoryRefundCaseRepository();
        RefundCase refund = RefundCase.create(1001L, 1001L, 20L, RefundCaseStatus.ELIGIBLE,
                new BigDecimal("188.00"), "eligible", "{}");
        refund.setId(30L);
        refunds.save(refund);
        HumanHandoffContextPacketBuilder builder = new HumanHandoffContextPacketBuilder(
                sessions(), new InMemorySessionEventRepository(), workflows,
                new InMemoryWorkflowStepRepository(), refunds, emptyTraceRuns(),
                mock(TraceQueryService.class), new RedactionService());

        HumanHandoffContextPacket packet = builder.build(new HumanHandoffContextPacketBuilder.Request(
                10L, 20L, 30L, "REFUND_REVIEW", "HIGH", "NEED_HUMAN", "{}"));

        assertThat(packet.completenessStatus()).isEqualTo("INSUFFICIENT");
        assertThat(packet.missingSections()).contains("REFUND_POLICY_RESULT");
        assertThat(packet.refundRuleDecision().status()).isEqualTo("UNKNOWN");
        assertThat(packet.refundRuleDecision().evidenceSufficientForManualDecision()).isFalse();
    }

    @Test
    void traceRepositoryFailureDegradesTraceWithoutDiscardingRefundFacts() {
        InMemoryWorkflowRepository workflows = new InMemoryWorkflowRepository();
        Workflow workflow = Workflow.create(10L, "order_refund_inquiry");
        workflow.setId(20L);
        workflows.save(workflow);
        InMemoryRefundCaseRepository refunds = new InMemoryRefundCaseRepository();
        RefundCase refund = RefundCase.create(1001L, 1001L, 20L, RefundCaseStatus.NEED_HUMAN,
                null, "manual", "{\"decision\":\"NEED_HUMAN\"}");
        refund.setId(30L);
        refunds.save(refund);
        TraceRunRepository failingTraceRuns = mock(TraceRunRepository.class);
        when(failingTraceRuns.findLatestBySessionIdAndWorkflowId(10L, 20L))
                .thenThrow(new IllegalStateException("database payload secret"));
        HumanHandoffContextPacketBuilder builder = new HumanHandoffContextPacketBuilder(
                sessions(), new InMemorySessionEventRepository(), workflows,
                new InMemoryWorkflowStepRepository(), refunds, failingTraceRuns,
                mock(TraceQueryService.class), new RedactionService());

        HumanHandoffContextPacket packet = builder.build(new HumanHandoffContextPacketBuilder.Request(
                10L, 20L, 30L, "REFUND_REVIEW", "HIGH", "NEED_HUMAN", "{}"));

        assertThat(packet.completenessStatus()).isEqualTo("PARTIAL");
        assertThat(packet.missingSections()).contains("TRACE");
        assertThat(packet.refundRuleDecision().status()).isEqualTo("NEED_HUMAN");
        assertThat(packet.refundRuleDecision().verified()).isTrue();
        assertThat(packet.riskWarnings()).noneMatch(value -> value.contains("secret"));
    }

    private static SessionEvent event(Long sessionId, int seqNo, SessionEventRole role, String content) {
        return SessionEvent.create(sessionId, seqNo, SessionEventType.MESSAGE, role, content, "{}");
    }

    private static InMemorySessionRepository sessions() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        Session session = Session.create(1001L, "WEB");
        session.setId(10L);
        sessions.save(session);
        return sessions;
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
