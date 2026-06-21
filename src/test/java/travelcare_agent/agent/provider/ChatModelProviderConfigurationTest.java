package travelcare_agent.agent.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ChatModelProviderConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ChatModelProviderConfiguration.class)
            .withBean(AgentProviderProperties.class)
            .withBean(ObjectMapper.class);

    @Test
    void defaultConfigurationCreatesOnlyMockProvider() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(DeepSeekChatModelProvider.class);
            assertThat(context.getBean(ChatModelProvider.class))
                    .isInstanceOf(MockChatModelProvider.class);
        });
    }
}
