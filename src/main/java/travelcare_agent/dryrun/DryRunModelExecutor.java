package travelcare_agent.dryrun;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import travelcare_agent.agent.provider.MockChatModelProvider;
import travelcare_agent.agent.provider.ModelMessage;
import travelcare_agent.agent.provider.ModelRequest;

import java.util.List;
import java.util.Map;

@Component
public class DryRunModelExecutor {
    private final MockChatModelProvider provider;
    private final ObjectMapper objectMapper;

    public DryRunModelExecutor(MockChatModelProvider provider, ObjectMapper objectMapper) {
        this.provider = provider;
        this.objectMapper = objectMapper;
    }

    public ModelResult generate(String deterministicAnswer) {
        return generate(deterministicAnswer, "stage7b-dry-run");
    }

    public ModelResult generate(String deterministicAnswer, String promptVersion) {
        try {
            var response = provider.call(new ModelRequest(
                    "mock-stage10a",
                    promptVersion,
                    List.of(new ModelMessage("user", "dry-run")),
                    0.0,
                    5000,
                    Map.of(
                            "operation", "RESPONSE_GENERATION",
                            "deterministicAnswer", deterministicAnswer
                    )
            ));
            JsonNode output = objectMapper.readTree(response.content());
            return new ModelResult(provider.providerName(), response.model(), output.path("answer").asText(), output);
        } catch (Exception ex) {
            throw new IllegalStateException("DRY_RUN_MODEL_FAILED", ex);
        }
    }

    public record ModelResult(String provider, String model, String answer, JsonNode output) {
    }
}
