package travelcare_agent.evaluation;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EvaluationPromptStubRegistryTest {
    private final EvaluationPromptStubRegistry registry = new EvaluationPromptStubRegistry();

    @Test
    void exposesOnlyControlledVersionsAndProducesDeterministicRegressions() {
        assertThat(registry.contains("stage8-default")).isTrue();
        assertThat(registry.contains("stage8-regression-policy-wording")).isTrue();
        assertThat(registry.contains("stage8-regression-unsafe")).isTrue();
        assertThat(registry.render("stage8-default", "eligible for refund", "ELIGIBLE"))
                .isEqualTo("eligible for refund");
        assertThat(registry.render("stage8-regression-policy-wording", "eligible for refund", "ELIGIBLE"))
                .doesNotContain("eligible for refund");
        assertThat(registry.render("stage8-regression-unsafe", "not eligible", "INELIGIBLE"))
                .contains("approved");
    }
}
