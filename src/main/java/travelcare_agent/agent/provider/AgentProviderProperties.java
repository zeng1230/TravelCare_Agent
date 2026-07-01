package travelcare_agent.agent.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "travelcare.agent")
public class AgentProviderProperties {

    private AgentProviderType provider = AgentProviderType.MOCK;
    private String model = "mock-stage10a";
    private String promptVersion = "stage10a-default";
    private int timeoutMs = 5000;
    private String apiKey = "";
    private String baseUrl = "https://api.deepseek.com";
    private Double temperature = 0.0;
    private DeepSeek deepseek = new DeepSeek();

    public AgentProviderType getProvider() {
        return provider;
    }

    public void setProvider(AgentProviderType provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public DeepSeek getDeepseek() {
        return deepseek;
    }

    public void setDeepseek(DeepSeek deepseek) {
        this.deepseek = deepseek == null ? new DeepSeek() : deepseek;
    }

    public static class DeepSeek {
        private DeepSeekBackendType backend = DeepSeekBackendType.LEGACY;

        public DeepSeekBackendType getBackend() {
            return backend;
        }

        public void setBackend(DeepSeekBackendType backend) {
            this.backend = backend == null ? DeepSeekBackendType.LEGACY : backend;
        }
    }
}
