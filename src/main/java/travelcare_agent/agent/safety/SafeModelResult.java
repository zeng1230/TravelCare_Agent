package travelcare_agent.agent.safety;

public record SafeModelResult<T>(
        T value,
        ModelSafetyDecision decision,
        boolean providerFallbackUsed
) {
}
