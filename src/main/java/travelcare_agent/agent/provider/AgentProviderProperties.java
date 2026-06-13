package travelcare_agent.agent.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "travelcare.agent")
public class AgentProviderProperties {

    private String provider = "mock";
    private DeepSeek deepseek = new DeepSeek("https://api.deepseek.com", "", "deepseek-chat", 5000);

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public DeepSeek getDeepseek() {
        return deepseek;
    }

    public void setDeepseek(DeepSeek deepseek) {
        this.deepseek = deepseek;
    }

    public record DeepSeek(String baseUrl, String apiKey, String model, int timeoutMs) {
    }
}
