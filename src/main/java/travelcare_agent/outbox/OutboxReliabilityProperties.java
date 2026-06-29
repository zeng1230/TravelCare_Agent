package travelcare_agent.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "travelcare.async.outbox")
public class OutboxReliabilityProperties {
    private int maxPublishAttempts = 3;
    private Duration retryDelay = Duration.ofMinutes(1);
    private Duration stalePublishingAfter = Duration.ofMinutes(5);
    private Duration confirmTimeout = Duration.ofSeconds(5);

    public int getMaxPublishAttempts() {
        return maxPublishAttempts;
    }

    public void setMaxPublishAttempts(int maxPublishAttempts) {
        this.maxPublishAttempts = maxPublishAttempts;
    }

    public Duration getRetryDelay() {
        return retryDelay;
    }

    public void setRetryDelay(Duration retryDelay) {
        this.retryDelay = retryDelay;
    }

    public Duration getStalePublishingAfter() {
        return stalePublishingAfter;
    }

    public void setStalePublishingAfter(Duration stalePublishingAfter) {
        this.stalePublishingAfter = stalePublishingAfter;
    }

    public Duration getConfirmTimeout() {
        return confirmTimeout;
    }

    public void setConfirmTimeout(Duration confirmTimeout) {
        this.confirmTimeout = confirmTimeout;
    }
}
