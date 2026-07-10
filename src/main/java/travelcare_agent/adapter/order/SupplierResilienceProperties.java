package travelcare_agent.adapter.order;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "travelcare.supplier.resilience")
public class SupplierResilienceProperties {

    private CircuitBreakerProperties circuitBreaker = new CircuitBreakerProperties();
    private RetryProperties retry = new RetryProperties();
    private TimeLimiterProperties timeLimiter = new TimeLimiterProperties();
    private ExecutorProperties executor = new ExecutorProperties();

    public CircuitBreakerProperties getCircuitBreaker() {
        return circuitBreaker;
    }

    public void setCircuitBreaker(CircuitBreakerProperties circuitBreaker) {
        this.circuitBreaker = circuitBreaker;
    }

    public RetryProperties getRetry() {
        return retry;
    }

    public void setRetry(RetryProperties retry) {
        this.retry = retry;
    }

    public TimeLimiterProperties getTimeLimiter() {
        return timeLimiter;
    }

    public void setTimeLimiter(TimeLimiterProperties timeLimiter) {
        this.timeLimiter = timeLimiter;
    }

    public ExecutorProperties getExecutor() {
        return executor;
    }

    public void setExecutor(ExecutorProperties executor) {
        this.executor = executor;
    }

    public static class CircuitBreakerProperties {
        private int slidingWindowSize = 10;
        private int minimumNumberOfCalls = 5;
        private float failureRateThreshold = 50.0f;
        private Duration slowCallDurationThreshold = Duration.ofMillis(3500);
        private float slowCallRateThreshold = 75.0f;
        private Duration waitDurationInOpenState = Duration.ofSeconds(10);
        private int permittedNumberOfCallsInHalfOpenState = 2;

        public int getSlidingWindowSize() { return slidingWindowSize; }
        public void setSlidingWindowSize(int value) { this.slidingWindowSize = value; }
        public int getMinimumNumberOfCalls() { return minimumNumberOfCalls; }
        public void setMinimumNumberOfCalls(int value) { this.minimumNumberOfCalls = value; }
        public float getFailureRateThreshold() { return failureRateThreshold; }
        public void setFailureRateThreshold(float value) { this.failureRateThreshold = value; }
        public Duration getSlowCallDurationThreshold() { return slowCallDurationThreshold; }
        public void setSlowCallDurationThreshold(Duration value) { this.slowCallDurationThreshold = value; }
        public float getSlowCallRateThreshold() { return slowCallRateThreshold; }
        public void setSlowCallRateThreshold(float value) { this.slowCallRateThreshold = value; }
        public Duration getWaitDurationInOpenState() { return waitDurationInOpenState; }
        public void setWaitDurationInOpenState(Duration value) { this.waitDurationInOpenState = value; }
        public int getPermittedNumberOfCallsInHalfOpenState() { return permittedNumberOfCallsInHalfOpenState; }
        public void setPermittedNumberOfCallsInHalfOpenState(int value) { this.permittedNumberOfCallsInHalfOpenState = value; }
    }

    public static class RetryProperties {
        private int maxAttempts = 2;
        private Duration waitDuration = Duration.ofMillis(100);

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int value) { this.maxAttempts = value; }
        public Duration getWaitDuration() { return waitDuration; }
        public void setWaitDuration(Duration value) { this.waitDuration = value; }
    }

    public static class TimeLimiterProperties {
        private Duration timeout = Duration.ofMillis(1500);

        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration value) { this.timeout = value; }
    }

    public static class ExecutorProperties {
        private int poolSize = 4;
        private int queueCapacity = 32;

        public int getPoolSize() { return poolSize; }
        public void setPoolSize(int value) { this.poolSize = value; }
        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int value) { this.queueCapacity = value; }
    }
}
