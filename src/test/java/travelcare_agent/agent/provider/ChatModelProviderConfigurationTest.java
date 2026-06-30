package travelcare_agent.agent.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;

class ChatModelProviderConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(ChatModelProviderConfiguration.class)
            .withUserConfiguration(BoundPropertiesConfiguration.class)
            .withBean(ObjectMapper.class);

    @Test
    void providerMockSelectsMockProvider() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(DeepSeekChatModelProvider.class);
            assertThat(context.getBean(ChatModelProvider.class))
                    .isInstanceOf(MockChatModelProvider.class);
        });
    }

    @Test
    void providerDeepseekSelectsDeepSeekProvider() {
        contextRunner
                .withPropertyValues(
                        "travelcare.agent.provider=deepseek",
                        "travelcare.agent.api-key=test-key"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ChatModelProvider.class))
                            .isInstanceOf(DeepSeekChatModelProvider.class);
                });
    }

    @Test
    void providerSpringAiSelectsSpringAiProvider() {
        contextRunner
                .withUserConfiguration(FakeSpringAiChatModelConfiguration.class)
                .withPropertyValues("travelcare.agent.provider=spring-ai")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(DeepSeekChatModelProvider.class);
                    assertThat(context.getBean(ChatModelProvider.class))
                            .isInstanceOf(SpringAiChatModelProvider.class);
                });
    }

    @Configuration
    @EnableConfigurationProperties(AgentProviderProperties.class)
    static class BoundPropertiesConfiguration {
    }

    @Configuration
    static class FakeSpringAiChatModelConfiguration {
        @Bean
        @Primary
        ChatModel chatModel() {
            return new ChatModel() {
                @Override
                public ChatResponse call(Prompt prompt) {
                    return new ChatResponse(java.util.List.of());
                }
            };
        }
    }
}
