package travelcare_agent.agent.provider;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MockChatModelProviderTest {

    @Test
    void returnsDeterministicIntentResponseWithoutUsage() {
        ChatModelProvider provider = new MockChatModelProvider();
        ModelRequest request = new ModelRequest(
                "mock-stage10a",
                "intent-classifier-v1",
                List.of(new ModelMessage("user", "prompt")),
                0.0,
                5000,
                Map.of(
                        "operation", "INTENT_CLASSIFICATION",
                        "message", "Can I refund order ORD_10?"
                )
        );

        ModelResponse first = provider.call(request);
        ModelResponse second = provider.call(request);

        assertThat(first).isEqualTo(second);
        assertThat(first.content()).isEqualTo(
                "{\"intent\":\"REFUND_INQUIRY\",\"confidence\":1.0,\"slots\":{\"orderNo\":\"ORD-10\",\"orderId\":null},\"citations\":[],\"riskFlags\":[]}");
        assertThat(first.model()).isEqualTo("mock-stage10a");
        assertThat(first.provider()).isEqualTo("mock");
        assertThat(first.usage()).isEqualTo(new ModelUsage(0, 0, 0));
        assertThat(first.latencyMs()).isZero();
        assertThat(first.finishReason()).isEqualTo("stop");
    }
}
