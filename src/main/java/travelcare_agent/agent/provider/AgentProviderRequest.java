package travelcare_agent.agent.provider;

import java.util.Map;

public record AgentProviderRequest(
        String operation,
        String promptVersion,
        String prompt,
        Map<String, Object> input
) {
}
