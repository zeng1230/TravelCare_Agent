package travelcare_agent.evaluation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BaselineComparisonRulesTest {
    private final BaselineComparisonRules rules = new BaselineComparisonRules();

    @Test
    void passedToSkippedIsNotComparedAndMakesRunPartial() {
        var baseline = facts("PASSED", "ELIGIBLE", "RESPONDED", "LOW", true, true);
        var current = facts("SKIPPED", "ELIGIBLE", "RESPONDED", "LOW", true, true);

        BaselineComparisonDecision decision = rules.compare(baseline, current);

        assertThat(decision.status()).isEqualTo(RegressionCaseStatus.NOT_COMPARED);
        assertThat(decision.partial()).isTrue();
    }

    @Test
    void detectsRegressionImprovementAndUnchanged() {
        var passed = facts("PASSED", "ELIGIBLE", "RESPONDED", "LOW", true, true);
        assertThat(rules.compare(passed, facts("FAILED", "ELIGIBLE", "RESPONDED", "LOW", false, true)).status())
                .isEqualTo(RegressionCaseStatus.REGRESSION);
        assertThat(rules.compare(facts("FAILED", "ELIGIBLE", "RESPONDED", "MEDIUM", false, true), passed).status())
                .isEqualTo(RegressionCaseStatus.IMPROVED);
        assertThat(rules.compare(passed, passed).status()).isEqualTo(RegressionCaseStatus.UNCHANGED);
    }

    @Test
    void missingCriticalFactIsNotCompared() {
        var baseline = facts("PASSED", "UNKNOWN", "RESPONDED", "LOW", true, true);
        var current = facts("PASSED", "ELIGIBLE", "RESPONDED", "LOW", true, true);

        assertThat(rules.compare(baseline, current).status())
                .isEqualTo(RegressionCaseStatus.NOT_COMPARED);
    }

    private EvaluationCaseResultFacts facts(String status, String policy, String workflow,
            String risk, Boolean output, Boolean safety) {
        return new EvaluationCaseResultFacts(status, policy, workflow, risk, output, safety);
    }
}
