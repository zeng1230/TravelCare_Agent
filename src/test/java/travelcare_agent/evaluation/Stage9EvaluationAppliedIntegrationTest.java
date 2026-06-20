package travelcare_agent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import travelcare_agent.agent.provider.DeepSeekAgentProvider;
import travelcare_agent.evaluation.entity.EvaluationDataset;
import travelcare_agent.evaluation.repository.EvaluationCaseResultRepository;
import travelcare_agent.trace.TraceService;
import travelcare_agent.trace.TraceSnapshotType;
import travelcare_agent.trace.repository.TraceRunRepository;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest
class Stage9EvaluationAppliedIntegrationTest {
    private static final Long USER_ID = 9901L;
    private static final LocalDateTime EVALUATED_AT = LocalDateTime.of(2026, 6, 20, 10, 0);

    @Autowired private EvaluationDatasetService datasets;
    @Autowired private EvaluationRunnerService runner;
    @Autowired private EvaluationCaseResultRepository results;
    @Autowired private TraceRunRepository traceRuns;
    @Autowired private TraceService traces;
    @Autowired private ObjectMapper json;
    @SpyBean private DeepSeekAgentProvider deepSeek;
    @MockBean private RabbitTemplate rabbit;

    @Test
    void stage9DatasetAppliesQualityScorersAndWritesPassingReport() throws Exception {
        List<Scenario> scenarios = scenarios();
        EvaluationDataset dataset = datasets.create(
                "stage9-quality-" + System.nanoTime(), "Stage 9 RAG Quality", "Stage 9B applied scorer cases");
        for (Scenario scenario : scenarios) {
            datasets.createCase(dataset.getId(), scenario.caseKey(), scenario.caseKey(),
                    createSourceTrace(scenario), scenario.expectation(),
                    scenario.businessLocked() ? "[\"stage9\",\"refund\"]" : "[\"stage9\",\"rag\"]", true);
        }
        datasets.activate(dataset.getId());

        reset(deepSeek, rabbit);
        var run = runner.start(dataset.getId(), "mock", "stage8-default", false);
        var caseResults = results.findResultsByRunId(run.getId());

        assertThat(run.getStatus()).isEqualTo("PASSED");
        assertThat(caseResults).hasSize(4).allMatch(result -> "PASSED".equals(result.getStatus()));
        assertApplied(caseResults, "answerable_with_valid_citation",
                "answerabilityDecision", "citationRequired", "citationSource", "expiredCitation",
                "ragFallback", "businessOverrideGuard");
        assertApplied(caseResults, "unanswerable_requires_fallback",
                "answerabilityDecision", "citationRequired", "ragFallback");
        assertApplied(caseResults, "expired_citation_rejected_or_filtered",
                "answerabilityDecision", "citationRequired", "expiredCitation");
        assertApplied(caseResults, "business_decision_not_overridden_by_rag",
                "answerabilityDecision", "businessOverrideGuard");

        String report = runner.report(run.getId());
        assertThat(report).contains("### Answerability / Citation", "answerable_with_valid_citation",
                "unanswerable_requires_fallback", "expired_citation_rejected_or_filtered",
                "business_decision_not_overridden_by_rag");
        for (String scorer : List.of("answerabilityDecision", "citationRequired", "citationSource",
                "expiredCitation", "ragFallback", "businessOverrideGuard")) {
            assertThat(report).containsPattern("scorer=" + scorer + ".*applied=true");
        }
        assertThat(Files.exists(Path.of("target", "evaluation", "runs", run.getId() + "_report.md"))).isTrue();
        verifyNoInteractions(deepSeek, rabbit);
    }

    private void assertApplied(List<travelcare_agent.evaluation.entity.EvaluationCaseResult> caseResults,
            String caseKey, String... scorers) throws Exception {
        String scoresJson = caseResults.stream().filter(result -> caseKey.equals(result.getCaseKey()))
                .findFirst().orElseThrow().getScoresJson();
        JsonNode scores = json.readTree(scoresJson);
        for (String scorer : scorers) {
            JsonNode score = findScore(scores, scorer);
            assertThat(score.path("applied").asBoolean()).as(caseKey + ":" + scorer).isTrue();
            assertThat(score.path("passed").asBoolean()).as(caseKey + ":" + scorer).isTrue();
        }
    }

    private JsonNode findScore(JsonNode scores, String scorer) {
        for (JsonNode score : scores) if (scorer.equals(score.path("scorer").asText())) return score;
        throw new AssertionError("Missing scorer " + scorer);
    }

    private Long createSourceTrace(Scenario scenario) {
        TraceService.RootTrace root = traces.startRootRun(9900L + scenario.chunkId(), USER_ID, null,
                "mock", "mock-agent", "stage9-source", Map.of("caseKey", scenario.caseKey()));
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("orderId", 9001L); order.put("orderNo", "ORD-9001"); order.put("userId", USER_ID);
        order.put("status", "PAID"); order.put("refundable", true);
        order.put("paidAmount", new BigDecimal("100.00")); order.put("departureTime", EVALUATED_AT.plusDays(5));

        traces.recordSnapshot(root.traceId(), root.rootSpanId(), TraceSnapshotType.USER_INPUT.name(),
                "FIXTURE", scenario.caseKey(), Map.of("message", scenario.caseKey(), "userId", USER_ID));
        traces.recordSnapshot(root.traceId(), root.rootSpanId(), TraceSnapshotType.CONTEXT_SUMMARY.name(),
                "FIXTURE", scenario.caseKey(), Map.of("caseKey", scenario.caseKey()));
        traces.recordSnapshot(root.traceId(), root.rootSpanId(), TraceSnapshotType.RETRIEVAL_SUMMARY.name(),
                "FIXTURE", scenario.caseKey(), Map.of("retrievalRunId", "run-" + scenario.chunkId(), "results", List.of()));
        traces.recordSnapshot(root.traceId(), root.rootSpanId(), TraceSnapshotType.TOOL_REQUEST.name(),
                "FIXTURE", scenario.caseKey(), Map.of("toolName", "GetOrderTool", "orderNo", "ORD-9001"));
        traces.recordSnapshot(root.traceId(), root.rootSpanId(), TraceSnapshotType.TOOL_RESULT.name(),
                "FIXTURE", scenario.caseKey(), Map.of("toolName", "GetOrderTool", "status", "SUCCEEDED", "result", order));
        traces.recordSnapshot(root.traceId(), root.rootSpanId(), TraceSnapshotType.POLICY_INPUT.name(),
                "FIXTURE", scenario.caseKey(), Map.of("evaluatedAt", EVALUATED_AT, "currentUserId", USER_ID, "order", order));
        traces.recordSnapshot(root.traceId(), root.rootSpanId(), TraceSnapshotType.POLICY_DECISION.name(),
                "FIXTURE", scenario.caseKey(), Map.of("decision", "ELIGIBLE", "reason", "eligible", "refundAmount", "100.00"));
        traces.recordSnapshot(root.traceId(), root.rootSpanId(), TraceSnapshotType.WORKFLOW_PATH.name(),
                "FIXTURE", scenario.caseKey(), Map.of("workflowType", "order_refund_inquiry", "status", "RESPONDED", "steps", List.of()));
        traces.recordSnapshot(root.traceId(), root.rootSpanId(), TraceSnapshotType.MODEL_INPUT.name(),
                "FIXTURE", scenario.caseKey(), Map.of("operation", "RESPONSE_GENERATION", "input", Map.of("deterministicAnswer", "eligible")));
        traces.recordSnapshot(root.traceId(), root.rootSpanId(), TraceSnapshotType.MODEL_OUTPUT.name(),
                "FIXTURE", scenario.caseKey(), Map.of("operation", "RESPONSE_GENERATION", "provider", "mock", "output", Map.of("answer", "eligible")));
        traces.recordSnapshot(root.traceId(), root.rootSpanId(), TraceSnapshotType.ANSWERABILITY_DECISION.name(),
                "FIXTURE", scenario.caseKey(), answerability(scenario));
        traces.recordSnapshot(root.traceId(), root.rootSpanId(), TraceSnapshotType.CITATION_SUMMARY.name(),
                "FIXTURE", scenario.caseKey(), citations(scenario));
        traces.finishRootRunSuccess(root.traceId(), null, null,
                Map.of("answer", scenario.fallbackUsed() ? "deterministic fallback" : "eligible", "fallbackUsed", scenario.fallbackUsed()));
        return traceRuns.findByTraceId(root.traceId()).orElseThrow().getId();
    }

    private Map<String, Object> answerability(Scenario scenario) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("status", scenario.status()); value.put("reasonCode", scenario.reasonCode());
        value.put("requiredAction", scenario.requiredAction()); value.put("citationPolicy", scenario.citationRequired() ? "REQUIRED" : "OPTIONAL");
        value.put("evidenceChunkIds", scenario.citationRequired() ? List.of(scenario.chunkId()) : List.of());
        value.put("businessDecisionLocked", scenario.businessLocked());
        value.put("ragMayExplainBusinessDecision", scenario.businessLocked() && scenario.citationRequired());
        value.put("ragMayOverrideBusinessDecision", false);
        return value;
    }

    private Map<String, Object> citations(Scenario scenario) {
        List<Map<String, Object>> finalCitations = scenario.citationRequired() ? List.of(citation(scenario, false)) : List.of();
        List<Map<String, Object>> rejected = scenario.expiredRejected() ? List.of(citation(scenario, true)) : List.of();
        return Map.of("citations", finalCitations, "rejectedCitationCandidates", rejected);
    }

    private Map<String, Object> citation(Scenario scenario, boolean expired) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("retrievalRunId", "run-" + scenario.chunkId()); value.put("chunkId", scenario.chunkId());
        value.put("documentId", scenario.documentId()); value.put("title", scenario.caseKey());
        value.put("sourceUri", "fixture://" + scenario.caseKey());
        value.put("effectiveFrom", EVALUATED_AT.minusDays(1));
        value.put("effectiveTo", expired ? EVALUATED_AT.minusHours(1) : EVALUATED_AT.plusDays(30));
        if (expired) value.put("reasonCode", "EXPIRED_SOURCE");
        return value;
    }

    private List<Scenario> scenarios() {
        return List.of(
                scenario("answerable_with_valid_citation", "ANSWERABLE", "SUFFICIENT_CONTEXT", "ALLOW_MODEL", true, false, true, false),
                scenario("unanswerable_requires_fallback", "UNANSWERABLE", "NO_RETRIEVAL", "FALLBACK_REPLY", false, true, false, false),
                scenario("expired_citation_rejected_or_filtered", "UNANSWERABLE", "EXPIRED_SOURCE", "FALLBACK_REPLY", false, false, false, true),
                scenario("business_decision_not_overridden_by_rag", "ANSWERABLE", "SUFFICIENT_CONTEXT", "ALLOW_MODEL", true, false, true, false)
        );
    }

    private Scenario scenario(String key, String status, String reason, String action,
            boolean citationRequired, boolean fallbackUsed, boolean businessLocked, boolean expiredRejected) {
        long offset = Math.abs(key.hashCode());
        long chunkId = 9_100_000L + offset;
        long documentId = 9_000_000L + offset;
        String expectation = expectation(status, reason, action, citationRequired, fallbackUsed,
                businessLocked, chunkId, documentId);
        return new Scenario(key, status, reason, action, citationRequired, fallbackUsed,
                businessLocked, expiredRejected, chunkId, documentId, expectation);
    }

    private String expectation(String status, String reason, String action, boolean citationRequired,
            boolean fallbackUsed, boolean businessLocked, long chunkId, long documentId) {
        Map<String, Object> expectation = new LinkedHashMap<>();
        expectation.put("expectedAnswerabilityStatus", status);
        expectation.put("expectedAnswerabilityReasonCode", reason);
        expectation.put("expectedRequiredAction", action);
        expectation.put("expectCitationRequired", citationRequired);
        if (citationRequired) {
            expectation.put("expectedCitationChunkIds", List.of(chunkId));
            expectation.put("expectedCitationDocumentIds", List.of(documentId));
        }
        expectation.put("expectNoExpiredCitation", true);
        expectation.put("expectedFallbackUsed", fallbackUsed);
        if (businessLocked) expectation.put("expectBusinessDecisionLocked", true);
        expectation.put("expectRagMayOverrideBusinessDecision", false);
        expectation.put("requireNoBusinessSideEffects", true);
        try { return json.writeValueAsString(expectation); } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    private record Scenario(String caseKey, String status, String reasonCode, String requiredAction,
            boolean citationRequired, boolean fallbackUsed, boolean businessLocked, boolean expiredRejected,
            long chunkId, long documentId, String expectation) {}
}
