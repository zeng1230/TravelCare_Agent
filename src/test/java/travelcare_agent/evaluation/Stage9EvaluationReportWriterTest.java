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
}
