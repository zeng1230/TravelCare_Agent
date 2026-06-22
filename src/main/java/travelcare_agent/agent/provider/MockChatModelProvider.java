package travelcare_agent.agent.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import travelcare_agent.agent.MockIntentClassifier;

import java.util.LinkedHashMap;
import java.util.Map;

public class MockChatModelProvider implements ChatModelProvider {

    private final MockIntentClassifier classifier = new MockIntentClassifier();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public ModelResponse call(ModelRequest request) {
        Map<String, Object> output = new LinkedHashMap<>();
        String operation = stringValue(request.metadata().get("operation"));
        if ("INTENT_CLASSIFICATION".equals(operation)) {
            MockIntentClassifier.IntentResult intent = classifier.classify(
                    stringValue(request.metadata().get("message"))
            );
            output.put("intent", intent.intent());
            output.put("confidence", 1.0);
            Map<String, Object> slots = new LinkedHashMap<>();
            slots.put("orderNo", intent.orderNo());
            slots.put("orderId", null);
            output.put("slots", slots);
            output.put("citations", java.util.List.of());
            output.put("riskFlags", java.util.List.of());
        } else if ("RESPONSE_GENERATION".equals(operation)) {
            output.put("intent", stringValue(request.metadata().get("intent")));
            output.put("confidence", 1.0);
            output.put("slots", Map.of());
            output.put("answerDraft", stringValue(request.metadata().get("deterministicAnswer")));
            Object citations = request.metadata().get("citations");
            output.put("citations", citations == null ? java.util.List.of() : citations);
            output.put("riskFlags", java.util.List.of());
        } else {
            throw new ModelCallException("MODEL_UNSUPPORTED_OPERATION", "Unsupported mock model operation");
        }
        String content = toJson(output);
        return new ModelResponse(
                content,
                "mock-stage10a",
                providerName(),
                new ModelUsage(0, 0, 0),
                0,
                "stop",
                content
        );
    }

    @Override
    public String providerName() {
        return "mock";
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new ModelCallException("MODEL_MOCK_SERIALIZATION_FAILED", "Mock model serialization failed", ex);
        }
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }
}
