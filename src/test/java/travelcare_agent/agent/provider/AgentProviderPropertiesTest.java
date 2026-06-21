package travelcare_agent.agent.provider;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentProviderPropertiesTest {

    @Test
    void defaultsToDeterministicMockConfiguration() {
        AgentProviderProperties properties = new AgentProviderProperties();

        assertThat(properties.getProvider()).isEqualTo(AgentProviderType.MOCK);
        assertThat(properties.getModel()).isEqualTo("mock-stage10a");
        assertThat(properties.getPromptVersion()).isEqualTo("stage10a-default");
        assertThat(properties.getTimeoutMs()).isEqualTo(5000);
        assertThat(properties.getApiKey()).isEmpty();
    }
}
