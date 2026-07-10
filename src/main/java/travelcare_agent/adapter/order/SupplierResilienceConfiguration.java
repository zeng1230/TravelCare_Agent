package travelcare_agent.adapter.order;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import travelcare_agent.observability.TravelCareMetrics;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
@ConditionalOnProperty(name = "travelcare.supplier.mode", havingValue = "http")
@EnableConfigurationProperties(SupplierResilienceProperties.class)
public class SupplierResilienceConfiguration {

    @Bean(name = "supplierResilienceExecutor", destroyMethod = "shutdown")
    ExecutorService supplierResilienceExecutor(SupplierResilienceProperties properties) {
        int poolSize = properties.getExecutor().getPoolSize();
        return new ThreadPoolExecutor(poolSize, poolSize, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.getExecutor().getQueueCapacity()),
                namedThreadFactory(), new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    CircuitBreaker supplierCircuitBreaker(SupplierResilienceProperties properties) {
        SupplierResilienceProperties.CircuitBreakerProperties config = properties.getCircuitBreaker();
        return CircuitBreaker.of("supplier-gateway", CircuitBreakerConfig.custom()
                .slidingWindowSize(config.getSlidingWindowSize())
                .minimumNumberOfCalls(config.getMinimumNumberOfCalls())
                .failureRateThreshold(config.getFailureRateThreshold())
                .slowCallDurationThreshold(config.getSlowCallDurationThreshold())
                .slowCallRateThreshold(config.getSlowCallRateThreshold())
                .waitDurationInOpenState(config.getWaitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(config.getPermittedNumberOfCallsInHalfOpenState())
                .recordException(SupplierResilienceConfiguration::isCircuitBreakerFailure)
                .ignoreException(SupplierResilienceConfiguration::isIgnoredCircuitBreakerException)
                .build());
    }

    @Bean
    Retry supplierRetry(SupplierResilienceProperties properties) {
        return Retry.of("supplier-gateway", RetryConfig.custom()
                .maxAttempts(properties.getRetry().getMaxAttempts())
                .waitDuration(properties.getRetry().getWaitDuration())
                .retryOnException(SupplierResilienceConfiguration::isRetryable)
                .build());
    }

    @Bean
    TimeLimiter supplierTimeLimiter(SupplierResilienceProperties properties) {
        return TimeLimiter.of("supplier-gateway", TimeLimiterConfig.custom()
                .timeoutDuration(properties.getTimeLimiter().getTimeout())
                .cancelRunningFuture(true)
                .build());
    }

    @Bean
    SupplierCallExecutor supplierCallExecutor(CircuitBreaker supplierCircuitBreaker, Retry supplierRetry,
                                              TimeLimiter supplierTimeLimiter,
                                              @Qualifier("supplierResilienceExecutor") ExecutorService executorService,
                                              TravelCareMetrics metrics) {
        return new SupplierCallExecutor(supplierCircuitBreaker, supplierRetry, supplierTimeLimiter, executorService, metrics);
    }

    private static boolean isRetryable(Throwable exception) {
        return exception instanceof SupplierGatewayClientException supplierException
                && supplierException.failureCode().retryable();
    }

    private static boolean isCircuitBreakerFailure(Throwable exception) {
        return !(exception instanceof SupplierGatewayClientException supplierException)
                || supplierException.failureCode().circuitBreakerFailure();
    }

    private static boolean isIgnoredCircuitBreakerException(Throwable exception) {
        return exception instanceof SupplierGatewayClientException supplierException
                && !supplierException.failureCode().circuitBreakerFailure();
    }

    private static ThreadFactory namedThreadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "supplier-resilience-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
