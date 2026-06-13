package travelcare_agent.agent.provider;

public record AgentProviderResponse(
        String rawText,
        String model,
        Integer inputTokens,
        Integer outputTokens
) {
}
