package travelcare_agent.agentops;

import org.junit.jupiter.api.Test;
import travelcare_agent.agent.provider.AgentProviderProperties;
import travelcare_agent.answerability.AnswerabilityService;
import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.common.result.ResultCode;
import travelcare_agent.conversation.entity.Session;
import travelcare_agent.conversation.repository.SessionRepository;
import travelcare_agent.retrieval.service.RetrievalQuery;
import travelcare_agent.retrieval.service.RetrievalService;
import travelcare_agent.retrieval.service.RetrievalSnippet;
import travelcare_agent.trace.TraceQueryService;
import travelcare_agent.trace.TraceSnapshotType;
import travelcare_agent.trace.entity.TraceRun;
import travelcare_agent.trace.entity.TraceSnapshot;
import travelcare_agent.trace.repository.TraceRunRepository;
import travelcare_agent.workflow.entity.Workflow;
import travelcare_agent.workflow.repository.WorkflowRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentOpsDebugServiceTest {

    @Test
    void rejectsNonDryRunRequestsWithDedicatedErrorCode() {
        AgentOpsDebugService service = serviceWithNoTrace(List.of());

        assertThatThrownBy(() -> service.debug(new AgentOpsDebugRequest(10L, null, "退款规则是什么？", false)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getResultCode())
                .isEqualTo(ResultCode.AGENTOPS_DRY_RUN_REQUIRED);
    }

    @Test
    void buildsExplanationFromExistingTraceSnapshotsAndSanitizesCitationUris() {
        TraceRun run = traceRun("trace-1", 10L, 20L);
        TraceQueryService traceQueryService = mock(TraceQueryService.class);
        when(traceQueryService.get("trace-1")).thenReturn(new TraceQueryService.TraceDetail(
                run,
                List.of(),
                List.of(),
                List.of(
                        snapshot(TraceSnapshotType.RETRIEVAL_SUMMARY.name(), """
                                {"results":[{"retrievalRunId":"ret-1","documentId":2001,"chunkId":1001,
                                "title":"Refund SOP user@example.com","sourceUri":"https://example.com/sop?token=abc&version=1#secret",
                                "sourceAnchor":"section-2 rawPrompt=hidden","policyVersion":"pv-7","effectiveTime":"2026-07-01T00:00:00",
                                "effectiveFrom":"2026-01-01T00:00:00","effectiveTo":"2026-12-31T00:00:00","score":1.0}]}
                                """),
                        snapshot(TraceSnapshotType.ANSWERABILITY_DECISION.name(), """
                                {"status":"ANSWERABLE","reasonCode":"SUFFICIENT_CONTEXT","requiredAction":"ALLOW_MODEL"}
                                """),
                        snapshot(TraceSnapshotType.CITATION_SUMMARY.name(), """
                                {"citations":[{"retrievalRunId":"ret-1","documentId":2001,"chunkId":1001,
                                "title":"Refund SOP","sourceUri":"https://example.com/sop?token=abc&version=1#secret",
                                "sourceAnchor":"section-2","policyVersion":"pv-7","effectiveTime":"2026-07-01T00:00:00",
                                "effectiveFrom":"2026-01-01T00:00:00","effectiveTo":"2026-12-31T00:00:00"}],
                                "rejectedCitationCandidates":[{"retrievalRunId":"ret-1","documentId":2002,"chunkId":1002,
                                "reasonCode":"LOW_MATCH"}]}
                                """),
                        snapshot(TraceSnapshotType.MODEL_SAFETY_DECISION.name(), """
                                {"safetyDecision":"ALLOW","reasonCode":"SAFE","riskFlags":[]}
                                """),
                        snapshot(TraceSnapshotType.FINAL_OUTPUT.name(), """
                                {"fallbackUsed":false,"answerabilityStatus":"ANSWERABLE"}
                                """)
                )));
        when(traceQueryService.diagnostics("trace-1")).thenReturn(new TraceQueryService.TraceDiagnostics(
                "trace-1", "SUCCEEDED", "mock", "mock-stage10a", "stage10a-default",
                List.of(), List.of(), List.of(), List.of(), List.of(), null,
                new TraceQueryService.RedactionSummary(0), List.of(), false, null, List.of(), List.of()));
        AgentOpsDebugService service = service(traceQueryService, Optional.of(run), List.of());

        AgentOpsDebugResponse response = service.debug(new AgentOpsDebugRequest(10L, 20L, "订单 ORD-1001 可以退款吗？", true));

        assertThat(response.traceId()).isEqualTo("trace-1");
        assertThat(response.evidenceMode()).isEqualTo(DebugEvidenceMode.TRACE_REPLAY);
        assertThat(response.debugMode()).isEqualTo("DRY_RUN");
        assertThat(response.modelProvider()).isEqualTo("mock");
        assertThat(response.promptVersion()).isEqualTo("stage10a-default");
        assertThat(response.retrieval().candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.documentId()).isEqualTo(2001L);
            assertThat(candidate.chunkId()).isEqualTo(1001L);
            assertThat(candidate.title()).doesNotContain("user@example.com");
            assertThat(candidate.sourceAnchor()).doesNotContain("rawPrompt", "hidden");
            assertThat(candidate.policyVersion()).isEqualTo("pv-7");
            assertThat(candidate.effectiveTime()).isEqualTo("2026-07-01T00:00:00");
            assertThat(candidate.sourceUri()).isEqualTo("https://example.com/sop?version=1");
        });
        assertThat(response.question()).doesNotContain("13812345678");
        assertThat(response.retrieval().acceptedCitations()).singleElement()
                .satisfies(citation -> assertThat(citation.sourceUri()).isEqualTo("https://example.com/sop?version=1"));
        assertThat(response.retrieval().rejectedCitations()).singleElement()
                .satisfies(citation -> assertThat(citation.rejectionReason()).isEqualTo("LOW_MATCH"));
        assertThat(response.answerability().decision()).isEqualTo("ANSWERABLE");
        assertThat(response.safety().decision()).isEqualTo("ALLOW");
        assertThat(response.finalRoute()).isEqualTo(DebugFinalRoute.ALLOW);
        assertThat(response.supplierGateway().participated()).isFalse();
        assertThat(response.supplierGateway().skippedReason()).contains("dry-run");
    }

    @Test
    void fallsBackToInMemoryRetrievalAndAnswerabilityWhenNoTraceExists() {
        RetrievalService retrievalService = mock(RetrievalService.class);
        when(retrievalService.retrieve(any(RetrievalQuery.class))).thenReturn(List.of(new RetrievalSnippet(
                "ret-2", 3001L, 4001L, "Current SOP", "refund content",
                "https://example.com/current?api_key=secret&ok=1",
                LocalDateTime.parse("2026-01-01T00:00:00"),
                LocalDateTime.parse("2026-12-31T00:00:00"),
                1.0
        )));
        AgentOpsDebugService service = service(mock(TraceQueryService.class), Optional.empty(), List.of(), retrievalService);

        AgentOpsDebugResponse response = service.debug(new AgentOpsDebugRequest(10L, null, "退款规则是什么？", null));

        assertThat(response.traceId()).isNull();
        assertThat(response.evidenceMode()).isEqualTo(DebugEvidenceMode.CURRENT_DIAGNOSTIC);
        assertThat(response.completenessStatus()).isEqualTo("PARTIAL");
        assertThat(response.missingSections()).contains("TRACE");
        assertThat(response.riskWarnings()).contains("TRACE_EVIDENCE_UNAVAILABLE");
        assertThat(response.diagnostic().status()).isEqualTo("AVAILABLE");
        assertThat(response.diagnostic().source()).isEqualTo("IN_MEMORY_OBSERVATION");
        assertThat(response.providerMode()).isEqualTo("mock");
        assertThat(response.retrieval().candidates()).singleElement()
                .satisfies(candidate -> assertThat(candidate.sourceUri()).isEqualTo("https://example.com/current?ok=1"));
        assertThat(response.answerability().decision()).isEqualTo("ANSWERABLE");
        assertThat(response.finalRoute()).isEqualTo(DebugFinalRoute.ALLOW);
        assertThat(response.diagnosticWarnings()).contains("NO_EXISTING_TRACE_FOUND_CURRENT_STATE_DIAGNOSTIC_ONLY");
        verify(retrievalService).retrieve(any(RetrievalQuery.class));
    }

    @Test
    void returnsUnknownFallbackWhenTraceAndCurrentObservationAreUnavailable() {
        RetrievalService retrievalService = mock(RetrievalService.class);
        when(retrievalService.retrieve(any(RetrievalQuery.class))).thenThrow(new IllegalStateException("raw secret"));
        AgentOpsDebugService service = service(mock(TraceQueryService.class), Optional.empty(), List.of(), retrievalService);

        AgentOpsDebugResponse response = service.debug(new AgentOpsDebugRequest(10L, null, "refund rules?", true));

        assertThat(response.traceId()).isNull();
        assertThat(response.completenessStatus()).isEqualTo("PARTIAL");
        assertThat(response.missingSections()).contains("TRACE", "ANSWERABILITY", "CITATION");
        assertThat(response.answerability().decision()).isEqualTo("UNKNOWN");
        assertThat(response.diagnostic().status()).isEqualTo("UNKNOWN");
        assertThat(response.diagnostic().source()).isNull();
        assertThat(response.finalRoute()).isEqualTo(DebugFinalRoute.FALLBACK);
        assertThat(response.diagnosticWarnings()).noneMatch(value -> value.contains("raw secret"));
    }

    @Test
    void mapsFinalRoutesFromSafetyAndAnswerabilitySignals() {
        assertThat(AgentOpsDebugService.mapFinalRoute("BLOCK", "ANSWERABLE", "ALLOW_MODEL", false))
                .isEqualTo(DebugFinalRoute.BLOCK);
        assertThat(AgentOpsDebugService.mapFinalRoute("CLARIFY", "ANSWERABLE", "ALLOW_MODEL", false))
                .isEqualTo(DebugFinalRoute.CLARIFY);
        assertThat(AgentOpsDebugService.mapFinalRoute("ALLOW", "UNANSWERABLE", "FALLBACK_REPLY", false))
                .isEqualTo(DebugFinalRoute.FALLBACK);
        assertThat(AgentOpsDebugService.mapFinalRoute("ALLOW", "ANSWERABLE", "ALLOW_MODEL", true))
                .isEqualTo(DebugFinalRoute.HANDOFF);
        assertThat(AgentOpsDebugService.mapFinalRoute("ALLOW", "ANSWERABLE", "ALLOW_MODEL", false))
                .isEqualTo(DebugFinalRoute.ALLOW);
    }

    private static AgentOpsDebugService serviceWithNoTrace(List<RetrievalSnippet> snippets) {
        RetrievalService retrievalService = mock(RetrievalService.class);
        when(retrievalService.retrieve(any(RetrievalQuery.class))).thenReturn(snippets);
        return service(mock(TraceQueryService.class), Optional.empty(), snippets, retrievalService);
    }

    private static AgentOpsDebugService service(TraceQueryService queryService, Optional<TraceRun> trace,
            List<RetrievalSnippet> snippets) {
        RetrievalService retrievalService = mock(RetrievalService.class);
        when(retrievalService.retrieve(any(RetrievalQuery.class))).thenReturn(snippets);
        return service(queryService, trace, snippets, retrievalService);
    }

    private static AgentOpsDebugService service(TraceQueryService queryService, Optional<TraceRun> trace,
            List<RetrievalSnippet> snippets, RetrievalService retrievalService) {
        SessionRepository sessions = mock(SessionRepository.class);
        Session session = Session.create("default", 1001L, "WEB");
        session.setId(10L);
        when(sessions.findById(10L)).thenReturn(Optional.of(session));
        WorkflowRepository workflows = mock(WorkflowRepository.class);
        Workflow workflow = Workflow.create(10L, "order_refund_inquiry");
        workflow.setId(20L);
        when(workflows.findById(20L)).thenReturn(Optional.of(workflow));
        TraceRunRepository traceRuns = mock(TraceRunRepository.class);
        when(traceRuns.findLatestBySessionIdAndWorkflowId(10L, 20L)).thenReturn(trace);
        when(traceRuns.findLatestBySessionIdAndWorkflowId(10L, null)).thenReturn(trace);
        AgentProviderProperties properties = new AgentProviderProperties();
        properties.setPromptVersion("stage10a-default");
        return new AgentOpsDebugService(
                sessions, workflows, traceRuns, queryService, retrievalService,
                new AnswerabilityService(), properties);
    }

    private static TraceRun traceRun(String traceId, Long sessionId, Long workflowId) {
        TraceRun run = new TraceRun();
        run.setTraceId(traceId);
        run.setSessionId(sessionId);
        run.setWorkflowId(workflowId);
        run.setProvider("mock");
        run.setModel("mock-stage10a");
        run.setPromptVersion("stage10a-default");
        run.setStatus("SUCCEEDED");
        return run;
    }

    private static TraceSnapshot snapshot(String type, String payload) {
        TraceSnapshot snapshot = new TraceSnapshot();
        snapshot.setSnapshotType(type);
        snapshot.setPayloadJson(payload);
        return snapshot;
    }
}
