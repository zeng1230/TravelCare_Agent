package travelcare_agent.agent.safety;

import org.springframework.stereotype.Component;

@Component
public class SafeFallbackAnswerGenerator {
    public String generate(ModelSafetyDecisionType type, ModelSafetyContext context) {
        if (type == ModelSafetyDecisionType.FALLBACK
                && context.authoritativeAnswer() != null && !context.authoritativeAnswer().isBlank()) {
            return context.authoritativeAnswer();
        }
        return switch (type) {
            case CLARIFY -> "Please provide an order reference so I can continue with the verified process.";
            case HANDOFF -> "This request needs manual verification. Customer support will continue the case.";
            case BLOCK -> "I can’t use that generated response. I’ll continue only with verified information and authorized actions.";
            case FALLBACK -> "I don’t have enough verified information to answer safely. Please provide more details or contact manual support.";
            case ALLOW -> context.authoritativeAnswer();
        };
    }
}
