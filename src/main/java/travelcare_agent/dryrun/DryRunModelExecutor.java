package travelcare_agent.dryrun;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class DryRunModelExecutor {
    private final ObjectMapper objectMapper;

    public DryRunModelExecutor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ModelResult generate(String deterministicAnswer) {
        return generate(deterministicAnswer, "stage7b-dry-run");
    }

    public ModelResult generate(String deterministicAnswer, String promptVersion) {
        try {
            var output = objectMapper.createObjectNode();
            output.put("intent", "REFUND_INQUIRY");
            output.put("confidence", 1.0);
            output.set("slots", objectMapper.createObjectNode());
            output.put("answerDraft", deterministicAnswer);
            output.set("citations", objectMapper.createArrayNode());
            output.set("riskFlags", objectMapper.createArrayNode());
            return new ModelResult("mock", "mock-stage10a", deterministicAnswer, output);
        } catch (Exception ex) {
            throw new IllegalStateException("DRY_RUN_MODEL_FAILED", ex);
        }
    }

    public record ModelResult(String provider, String model, String answer, JsonNode output) {
    }
}
