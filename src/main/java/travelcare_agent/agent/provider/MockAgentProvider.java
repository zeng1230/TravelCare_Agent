package travelcare_agent.agent.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import travelcare_agent.agent.MockIntentClassifier;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class MockAgentProvider implements AgentProvider {

    private final MockIntentClassifier classifier = new MockIntentClassifier();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public AgentProviderResponse invoke(AgentProviderRequest request) {
        Map<String, Object> output = new LinkedHashMap<>();
        if ("INTENT_CLASSIFICATION".equals(request.operation())) {
            MockIntentClassifier.IntentResult intent = classifier.classify(stringValue(request.input().get("message")));
            output.put("intent", intent.intent());
            output.put("orderNo", intent.orderNo());
        } else if ("RESPONSE_GENERATION".equals(request.operation())) {
            output.put("answer", stringValue(request.input().get("deterministicAnswer")));
        } else {
            throw new IllegalArgumentException("Unsupported mock provider operation: " + request.operation());
        }
        return new AgentProviderResponse(toJson(output), "mock-agent", 0, 0);
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("MOCK_PROVIDER_SERIALIZATION_FAILED");
        }
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }
}
