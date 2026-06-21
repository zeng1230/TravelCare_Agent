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
            output.put("orderNo", intent.orderNo());
        } else if ("RESPONSE_GENERATION".equals(operation)) {
            output.put("answer", stringValue(request.metadata().get("deterministicAnswer")));
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
