package travelcare_agent.adapter.order;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "travelcare.supplier.gateway")
public class SupplierGatewayProperties {

    private String baseUrl = "http://localhost:8081";
    private long connectTimeoutMs = 1000;
    private long readTimeoutMs = 1500;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = trimTrailingSlash(baseUrl);
    }

    public Duration getConnectTimeout() {
        return Duration.ofMillis(connectTimeoutMs);
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeoutMs = connectTimeout.toMillis();
    }

    public Duration getReadTimeout() {
        return Duration.ofMillis(readTimeoutMs);
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeoutMs = readTimeout.toMillis();
    }

    public long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(long connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public long getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(long readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:8081";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
