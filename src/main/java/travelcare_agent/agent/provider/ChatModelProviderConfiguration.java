package travelcare_agent.agent.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.ai.chat.model.ChatModel;

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
    @ConditionalOnProperty(prefix = "travelcare.agent", name = "provider", havingValue = "spring-ai")
    public SpringAiChatModelProvider springAiChatModelProvider(
            AgentProviderProperties properties,
            ObjectProvider<ChatModel> chatModel,
            ObjectMapper objectMapper
    ) {
        return new SpringAiChatModelProvider(chatModel.getIfAvailable(), properties, objectMapper);
    }

    @Bean
    @Primary
    public ChatModelProvider chatModelProvider(
            AgentProviderProperties properties,
            MockChatModelProvider mockProvider,
            ObjectProvider<DeepSeekChatModelProvider> deepSeekProvider,
            ObjectProvider<SpringAiChatModelProvider> springAiProvider
    ) {
        return switch (properties.getProvider()) {
            case DEEPSEEK -> deepSeekProvider.getObject();
            case SPRING_AI -> springAiProvider.getObject();
            case MOCK -> mockProvider;
        };
    }

}
