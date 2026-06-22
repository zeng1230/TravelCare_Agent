package travelcare_agent.agent.safety;

import java.util.List;

public record ModelSafetyDecision(
        ModelSafetyDecisionType type,
        String reasonCode,
        List<RiskFlag> riskFlags,
        String safeAnswer
) {
    public ModelSafetyDecision {
        riskFlags = riskFlags == null ? List.of() : List.copyOf(riskFlags);
    }
}
