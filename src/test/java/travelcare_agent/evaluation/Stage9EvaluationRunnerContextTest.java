package travelcare_agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import travelcare_agent.dryrun.*;
import travelcare_agent.evaluation.entity.*;
import travelcare_agent.evaluation.repository.*;
import travelcare_agent.evaluation.scoring.EvaluationScoringContext;
import travelcare_agent.evaluation.scoring.SideEffectCheckResult;
import travelcare_agent.trace.TraceQueryService;
import travelcare_agent.trace.entity.*;
import travelcare_agent.trace.repository.TraceRunRepository;

import java.lang.reflect.Method;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class Stage9EvaluationRunnerContextTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void runnerContextExtractsStage9FieldsAndKeepsRawSnapshots() throws Exception {
        TraceRun dryRun = new TraceRun();
        dryRun.setId(44L);
        dryRun.setTraceId("dry-9b");
        TraceRunRepository traceRuns = mock(TraceRunRepository.class);
        when(traceRuns.findByTraceId("dry-9b")).thenReturn(Optional.of(dryRun));
        EvaluationRunnerService service = new EvaluationRunnerService(
                mock(EvaluationDatasetRepository.class), mock(EvaluationCaseRepository.class),
                mock(EvaluationRunRepository.class), mock(EvaluationCaseResultRepository.class),
                traceRuns, mock(DryRunReadinessChecker.class), mock(DiagnosticDryRunService.class),
                mock(TraceQueryService.class), mock(TraceDiffService.class), List.of(),
                new EvaluationPromptStubRegistry(), mock(EvaluationSideEffectGuard.class),
                mock(EvaluationRunReportWriter.class), json,
                Clock.fixed(Instant.parse("2026-06-15T00:00:00Z"), ZoneOffset.UTC));

        EvaluationRun run = new EvaluationRun();
        run.setPromptStubVersion("stage8-default");
        run.setProviderMode("mock");
        EvaluationCase evaluationCase = new EvaluationCase();
        evaluationCase.setId(9L);
        evaluationCase.setSourceTraceId(8L);
        evaluationCase.setExpectationJson("{}");
        TraceQueryService.TraceDetail detail = new TraceQueryService.TraceDetail(new TraceRun(), List.of(), List.of(), List.of(
                snapshot("ANSWERABILITY_DECISION", """
                        {"status":"UNANSWERABLE","reasonCode":"NO_RETRIEVAL","requiredAction":"FALLBACK_REPLY",
                         "businessDecisionLocked":true,"ragMayExplainBusinessDecision":false,
                         "ragMayOverrideBusinessDecision":false}
                        """),
                snapshot("CITATION_SUMMARY", """
                        {"citations":[],"rejectedCitationCandidates":[{"chunkId":501,"reasonCode":"LOW_MATCH"}]}
                        """),
                snapshot("FINAL_OUTPUT", """
                        {"answer":"fallback","fallbackUsed":true}
                        """)
        ));

        Method method = EvaluationRunnerService.class.getDeclaredMethod("context", EvaluationRun.class,
                EvaluationCase.class, DryRunResult.class, TraceQueryService.TraceDetail.class, SideEffectCheckResult.class);
        method.setAccessible(true);
        EvaluationScoringContext context = (EvaluationScoringContext) method.invoke(service, run, evaluationCase,
                DryRunResult.succeeded("source", "dry-9b", null), detail,
                new SideEffectCheckResult(true, Map.of(), Map.of(), null));

        assertThat(context.answerabilityStatus()).isEqualTo("UNANSWERABLE");
        assertThat(context.answerabilityReasonCode()).isEqualTo("NO_RETRIEVAL");
        assertThat(context.requiredAction()).isEqualTo("FALLBACK_REPLY");
        assertThat(context.businessDecisionLocked()).isTrue();
        assertThat(context.ragMayOverrideBusinessDecision()).isFalse();
        assertThat(context.fallbackUsed()).isTrue();
        assertThat(context.answerabilityDecisionSnapshot()).isNotNull();
        assertThat(context.citationSummarySnapshot()).isNotNull();
        assertThat(context.rejectedCitationCandidates().get(0).path("chunkId").asLong()).isEqualTo(501L);
    }

    @Test
    void runnerContextExtractsPr3cSafetySupplierAndProviderFallbackFields() throws Exception {
        TraceRun dryRun = new TraceRun();
        dryRun.setId(45L);
        dryRun.setTraceId("dry-3c");
        TraceRunRepository traceRuns = mock(TraceRunRepository.class);
        when(traceRuns.findByTraceId("dry-3c")).thenReturn(Optional.of(dryRun));
        EvaluationRunnerService service = new EvaluationRunnerService(
                mock(EvaluationDatasetRepository.class), mock(EvaluationCaseRepository.class),
                mock(EvaluationRunRepository.class), mock(EvaluationCaseResultRepository.class),
                traceRuns, mock(DryRunReadinessChecker.class), mock(DiagnosticDryRunService.class),
                mock(TraceQueryService.class), mock(TraceDiffService.class), List.of(),
                new EvaluationPromptStubRegistry(), mock(EvaluationSideEffectGuard.class),
                mock(EvaluationRunReportWriter.class), json,
                Clock.fixed(Instant.parse("2026-06-15T00:00:00Z"), ZoneOffset.UTC));

        EvaluationRun run = new EvaluationRun();
        run.setPromptStubVersion("stage8-default");
        run.setProviderMode("mock");
        EvaluationCase evaluationCase = new EvaluationCase();
        evaluationCase.setId(10L);
        evaluationCase.setSourceTraceId(9L);
        evaluationCase.setExpectationJson("{}");
        TraceQueryService.TraceDetail detail = new TraceQueryService.TraceDetail(new TraceRun(), List.of(
                span("TOOL", "GetOrderTool", "FAILED", "SUPPLIER_TIMEOUT"),
                span("FALLBACK", "model-provider-fallback", "SUCCEEDED", null)
        ), List.of(event("FALLBACK", "model-provider-fallback")), List.of(
                snapshot("MODEL_SAFETY_DECISION", """
                        {"safetyDecision":"BLOCK","reasonCode":"AUTHORITATIVE_DECISION_CONFLICT",
                         "riskFlags":[{"code":"REFUND_CONFLICT","severity":"CRITICAL"}]}
                        """),
                snapshot("FINAL_OUTPUT", """
                        {"answer":"fallback","fallbackUsed":true}
                        """)
        ));

        Method method = EvaluationRunnerService.class.getDeclaredMethod("context", EvaluationRun.class,
                EvaluationCase.class, DryRunResult.class, TraceQueryService.TraceDetail.class, SideEffectCheckResult.class);
        method.setAccessible(true);
        EvaluationScoringContext context = (EvaluationScoringContext) method.invoke(service, run, evaluationCase,
                DryRunResult.succeeded("source", "dry-3c", null), detail,
                new SideEffectCheckResult(true, Map.of(), Map.of(), null));

        assertThat(context.safetyDecision).isEqualTo("BLOCK");
        assertThat(context.safetyReasonCode).isEqualTo("AUTHORITATIVE_DECISION_CONFLICT");
        assertThat(context.safetyRiskFlags).contains("REFUND_CONFLICT");
        assertThat(context.supplierGatewayParticipated).isTrue();
        assertThat(context.supplierFailureCode).isEqualTo("SUPPLIER_TIMEOUT");
        assertThat(context.providerFallbackUsed).isTrue();
        assertThat(context.leakageCheckText).contains("MODEL_SAFETY_DECISION").doesNotContain("fallback");
    }

    private TraceSnapshot snapshot(String type, String payload) {
        TraceSnapshot snapshot = new TraceSnapshot();
        snapshot.setSnapshotType(type);
        snapshot.setPayloadJson(payload);
        return snapshot;
    }

    private TraceSpan span(String spanType, String name, String status, String errorCode) {
        TraceSpan span = new TraceSpan();
        span.setSpanType(spanType);
        span.setName(name);
        span.setStatus(status);
        span.setErrorCode(errorCode);
        return span;
    }

    private travelcare_agent.trace.entity.TraceEvent event(String eventType, String name) {
        travelcare_agent.trace.entity.TraceEvent event = new travelcare_agent.trace.entity.TraceEvent();
        event.setEventType(eventType);
        event.setName(name);
        return event;
    }
}
