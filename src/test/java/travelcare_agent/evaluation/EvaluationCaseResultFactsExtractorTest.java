package travelcare_agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import travelcare_agent.evaluation.entity.EvaluationCaseResult;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationCaseResultFactsExtractorTest {
    @Test
    void extractsStableComparisonFieldsFromScoresJson() {
        EvaluationCaseResult result = new EvaluationCaseResult();
        result.setStatus("PASSED");
        result.setRiskLevel("LOW");
        result.setScoresJson("""
                [
                  {"scorer":"policyDecision","passed":true,"actual":"ELIGIBLE","applied":true},
                  {"scorer":"workflowOutcome","passed":true,"actual":"RESPONDED","applied":true},
                  {"scorer":"outputAssertions","passed":true,"actual":"ok","applied":true},
                  {"scorer":"sideEffectSafety","passed":true,"actual":true,"applied":true}
                ]
                """);

        EvaluationCaseResultFacts facts =
                new EvaluationCaseResultFactsExtractor(new ObjectMapper()).extract(result);

        assertThat(facts.caseStatus()).isEqualTo("PASSED");
        assertThat(facts.policyDecision()).isEqualTo("ELIGIBLE");
        assertThat(facts.workflowStatus()).isEqualTo("RESPONDED");
        assertThat(facts.riskLevel()).isEqualTo("LOW");
        assertThat(facts.outputAssertionPassed()).isTrue();
        assertThat(facts.sideEffectSafetyPassed()).isTrue();
    }
}
