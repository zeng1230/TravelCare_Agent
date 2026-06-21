package travelcare_agent.agent.provider;

import java.util.List;
import java.util.Map;

public record ModelRequest(
        String model,
        String promptVersion,
        List<ModelMessage> messages,
        Double temperature,
        int timeoutMs,
        Map<String, Object> metadata
) {
    public ModelRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
