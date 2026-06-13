package travelcare_agent.agent.provider;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MockAgentProviderTest {

    @Test
    void returnsStableStructuredIntentOutput() {
        MockAgentProvider provider = new MockAgentProvider();
        AgentProviderRequest request = new AgentProviderRequest(
                "INTENT_CLASSIFICATION",
                "intent-classifier-v1",
                "prompt",
                Map.of("message", "Can I refund order ORD_10?")
        );

        AgentProviderResponse first = provider.invoke(request);
        AgentProviderResponse second = provider.invoke(request);

        assertThat(first).isEqualTo(second);
        assertThat(first.rawText()).isEqualTo("{\"intent\":\"REFUND_INQUIRY\",\"orderNo\":\"ORD-10\"}");
        assertThat(first.model()).isEqualTo("mock-agent");
    }
}
