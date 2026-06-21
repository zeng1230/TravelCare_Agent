package travelcare_agent.agent.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class ChatModelProviderConfiguration {

    @Bean
    public MockChatModelProvider mockChatModelProvider() {
        return new MockChatModelProvider();
    }

    @Bean
    @ConditionalOnProperty(prefix = "travelcare.agent", name = "provider", havingValue = "deepseek")
    public DeepSeekChatModelProvider deepSeekChatModelProvider(
            AgentProviderProperties properties,
            ObjectMapper objectMapper
    ) {
        return new DeepSeekChatModelProvider(properties, objectMapper);
    }

    @Bean
    @Primary
    public ChatModelProvider chatModelProvider(
            AgentProviderProperties properties,
            MockChatModelProvider mockProvider,
            ObjectProvider<DeepSeekChatModelProvider> deepSeekProvider
    ) {
        return properties.getProvider() == AgentProviderType.DEEPSEEK
                ? deepSeekProvider.getObject()
                : mockProvider;
    }
}
