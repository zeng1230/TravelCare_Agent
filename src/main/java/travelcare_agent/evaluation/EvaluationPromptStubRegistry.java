package travelcare_agent.evaluation;

import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class EvaluationPromptStubRegistry {
    private static final Set<String> VALUES = Set.of("stage8-default", "stage8-regression-policy-wording", "stage8-regression-unsafe");

    public boolean contains(String v) {
        return VALUES.contains(v);
    }

    public String defaultVersion() {
        return "stage8-default";
    }

    public String render(String version, String deterministicAnswer, String policyDecision) {
        if ("stage8-regression-policy-wording".equals(version) && "ELIGIBLE".equals(policyDecision))
            return "Refund processing is unavailable for this order.";
        if ("stage8-regression-unsafe".equals(version) && !"ELIGIBLE".equals(policyDecision))
            return "Refund is approved for this order.";
        return deterministicAnswer;
    }
}
