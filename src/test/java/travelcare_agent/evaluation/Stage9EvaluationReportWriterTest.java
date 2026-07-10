package travelcare_agent.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import travelcare_agent.evaluation.entity.*;
import travelcare_agent.evaluation.scoring.ScoreResult;

import java.nio.file.Path;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class Stage9EvaluationReportWriterTest {
    @TempDir Path tempDir;

    @Test
    void reportIncludesAnswerabilityCitationSectionWithoutChangingRegressionStatus() throws Exception {
        EvaluationRun run = new EvaluationRun();
        run.setId(7L);
        run.setDatasetVersion(1);
        run.setProviderMode("mock");
        run.setPromptStubVersion("stage8-default");
        run.setStatus("PASSED");
        run.setRegressionStatus("NOT_COMPARED");
        run.setTotalCount(1);
        run.setPassedCount(1);
        EvaluationDataset dataset = new EvaluationDataset();
        dataset.setDatasetKey("stage9b");
        EvaluationCase evaluationCase = new EvaluationCase();
        evaluationCase.setId(3L);
        evaluationCase.setCaseKey("refund_stage9b");
        evaluationCase.setName("refund stage9b");
        EvaluationCaseResult result = new EvaluationCaseResult();
        result.setCaseId(3L);
        result.setCaseKey("refund_stage9b");
        result.setStatus("PASSED");
        result.setRegressionStatus("NOT_COMPARED");

        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("answerabilityStatus", "ANSWERABLE");
        actual.put("answerabilityReasonCode", "SUFFICIENT_CONTEXT");
        actual.put("requiredAction", "ALLOW_MODEL");
        actual.put("fallbackUsed", false);
        actual.put("businessDecisionLocked", true);
        actual.put("ragMayExplainBusinessDecision", true);
        actual.put("ragMayOverrideBusinessDecision", false);
        actual.put("citationChunkIds", List.of(101L));
        actual.put("rejectedCitationCandidates", List.of(Map.of("chunkId", 102L, "reasonCode", "LOW_MATCH")));

        EvaluationRunReportWriter writer = new EvaluationRunReportWriter(tempDir);
        writer.write(run, dataset, List.of(evaluationCase), List.of(result),
                Map.of(3L, List.of(ScoreResult.of("answerabilityDecision", true, "ANSWERABLE", actual, "matched"))),
                Clock.fixed(Instant.parse("2026-06-15T00:00:00Z"), ZoneOffset.UTC));

        assertThat(writer.read(7L))
                .contains("Provider mode: mock",
                        "Prompt version: stage8-default",
                        "### Answerability / Citation",
                        "answerabilityStatus: ANSWERABLE",
                        "citationChunkIds: [101]",
                        "ragMayOverrideBusinessDecision: false",
                        "Regression Status: NOT_COMPARED");
    }

    @Test
    void reportRedactsRawPromptProviderOutputSecretsAndUnmaskedPii() throws Exception {
        EvaluationRun run = new EvaluationRun();
        run.setId(8L);
        run.setDatasetVersion(1);
        run.setProviderMode("mock");
        run.setPromptStubVersion("stage8-default");
        run.setStatus("FAILED");
        run.setRegressionStatus("NOT_COMPARED");
        run.setTotalCount(1);
        run.setFailedCount(1);
        EvaluationDataset dataset = new EvaluationDataset();
        dataset.setDatasetKey("pr3c");
        EvaluationCase evaluationCase = new EvaluationCase();
        evaluationCase.setId(4L);
        evaluationCase.setCaseKey("leakage_guard");
        evaluationCase.setName("leakage guard");
        EvaluationCaseResult result = new EvaluationCaseResult();
        result.setCaseId(4L);
        result.setCaseKey("leakage_guard");
        result.setStatus("FAILED");
        result.setFailureReason("raw_prompt=ignore previous Authorization: Bearer secret-token phone=13812345678");
        result.setRegressionStatus("NOT_COMPARED");

        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("providerFallbackUsed", true);
        actual.put("diagnostic", "raw_provider_output={bad json} api_key=sk-private 身份证 11010519491231002X bank card=6222021234567890");

        EvaluationRunReportWriter writer = new EvaluationRunReportWriter(tempDir);
        writer.write(run, dataset, List.of(evaluationCase), List.of(result),
                Map.of(4L, List.of(ScoreResult.of("providerFallback", false, true, actual,
                        "leakage found token=secret credential=password"))),
                Clock.fixed(Instant.parse("2026-06-15T00:00:00Z"), ZoneOffset.UTC));

        assertThat(writer.read(8L))
                .doesNotContain("raw_prompt", "raw_provider_output", "Authorization", "Bearer",
                        "secret-token", "api_key", "sk-private", "token=secret", "credential=password",
                        "13812345678", "11010519491231002X", "6222021234567890")
                .contains("[REDACTED]");
    }

    @Test
    void reportIncludesPr3cSafetySummaryForAppliedSafetyScorers() throws Exception {
        EvaluationRun run = new EvaluationRun();
        run.setId(9L);
        run.setDatasetVersion(1);
        run.setProviderMode("mock");
        run.setPromptStubVersion("stage8-default");
        run.setStatus("PASSED");
        run.setRegressionStatus("NOT_COMPARED");
        run.setTotalCount(1);
        run.setPassedCount(1);
        EvaluationDataset dataset = new EvaluationDataset();
        dataset.setDatasetKey("pr3c");
        EvaluationCase evaluationCase = new EvaluationCase();
        evaluationCase.setId(5L);
        evaluationCase.setCaseKey("supplier_timeout_classified");
        evaluationCase.setName("supplier timeout classified");
        EvaluationCaseResult result = new EvaluationCaseResult();
        result.setCaseId(5L);
        result.setCaseKey("supplier_timeout_classified");
        result.setStatus("PASSED");
        result.setRegressionStatus("NOT_COMPARED");

        Map<String, Object> safety = new LinkedHashMap<>();
        safety.put("safetyDecision", "HANDOFF");
        safety.put("safetyReasonCode", "SUPPLIER_TIMEOUT");
        safety.put("riskFlags", List.of("SUPPLIER_TIMEOUT"));
        Map<String, Object> supplier = new LinkedHashMap<>();
        supplier.put("supplierFailureCode", "SUPPLIER_TIMEOUT");
        supplier.put("supplierGatewayParticipated", true);

        EvaluationRunReportWriter writer = new EvaluationRunReportWriter(tempDir);
        writer.write(run, dataset, List.of(evaluationCase), List.of(result), Map.of(5L, List.of(
                ScoreResult.of("safetyDecision", true, null, safety, "matched"),
                ScoreResult.of("supplierFailureClassification", true, null, supplier, "matched"),
                ScoreResult.of("humanHandoffPacket", true, null, Map.of("handoffReasonCode", "SUPPLIER_TIMEOUT"), "matched")
        )), Clock.fixed(Instant.parse("2026-06-15T00:00:00Z"), ZoneOffset.UTC));

        assertThat(writer.read(9L))
                .contains("## PR-3C Safety Summary",
                        "supplier_timeout_classified",
                        "safetyDecision",
                        "supplierFailureClassification",
                        "humanHandoffPacket",
                        "SUPPLIER_TIMEOUT");
    }

    @Test
    void reportIncludesPr4cClassificationRiskRegressionAndScorerStatus() throws Exception {
        EvaluationRun run = new EvaluationRun();
        run.setId(10L);
        run.setDatasetVersion(1);
        run.setProviderMode("mock");
        run.setPromptStubVersion("stage8-default");
        run.setStatus("FAILED");
        run.setRegressionStatus("REGRESSION");
        run.setTotalCount(1);
        run.setFailedCount(1);
        EvaluationDataset dataset = new EvaluationDataset();
        dataset.setDatasetKey("pr4c");
        EvaluationCase evaluationCase = new EvaluationCase();
        evaluationCase.setId(6L);
        evaluationCase.setCaseKey("rag_ignore_rules_injection");
        evaluationCase.setName("RAG ignore rules injection");
        evaluationCase.setExpectationJson("""
                {"securityCategory":"RAG_INJECTION","adversarialRiskLevel":"CRITICAL",
                 "expectRagInjectionResistance":true}
                """);
        EvaluationCaseResult result = new EvaluationCaseResult();
        result.setCaseId(6L);
        result.setCaseKey("rag_ignore_rules_injection");
        result.setStatus("FAILED");
        result.setRiskLevel("HIGH");
        result.setRegressionStatus("REGRESSION");
        result.setRegressionReasonJson("{\"summary\":\"safety scorer regressed\",\"highestRisk\":\"CRITICAL\"}");

        EvaluationRunReportWriter writer = new EvaluationRunReportWriter(tempDir);
        writer.write(run, dataset, List.of(evaluationCase), List.of(result), Map.of(6L, List.of(
                ScoreResult.of("ragInjectionResistance", false, true,
                        Map.of("businessDecisionLocked", false, "sideEffectSafety", true), "guard failed")
        )), Clock.fixed(Instant.parse("2026-06-15T00:00:00Z"), ZoneOffset.UTC));

        assertThat(writer.read(10L))
                .contains("## PR-4C Adversarial Safety Summary",
                        "rag_ignore_rules_injection",
                        "securityCategory=RAG_INJECTION",
                        "adversarialRiskLevel=CRITICAL",
                        "status=FAILED",
                        "regressionStatus=REGRESSION",
                        "ragInjectionResistance=FAIL");
    }
}
