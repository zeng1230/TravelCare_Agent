package travelcare_agent.agent.provider;

public record ModelResponse(
        String content,
        String model,
        String provider,
        ModelUsage usage,
        long latencyMs,
        String finishReason,
        String rawResponse
) {
}
