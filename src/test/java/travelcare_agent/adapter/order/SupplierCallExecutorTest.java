package travelcare_agent.adapter.order;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import travelcare_agent.observability.TravelCareMetrics;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupplierCallExecutorTest {

    @Test
    void retriesTechnicalFailureOnceAndRecordsOnlyTheRetry() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        try {
            SupplierCallExecutor executor = executor(registry, executorService);
            AtomicInteger attempts = new AtomicInteger();

            String result = executor.execute("http", "gateway", () -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new SupplierGatewayClientException(SupplierFailureCode.SUPPLIER_UNAVAILABLE, "temporary");
                }
                return "ok";
            });

            assertThat(result).isEqualTo("ok");
            assertThat(attempts).hasValue(2);
            assertThat(registry.get("travelcare.supplier.retry.total")
                    .tag("adapter", "http")
                    .tag("supplier", "gateway")
                    .tag("outcome", "retry")
                    .tag("failureCode", "SUPPLIER_UNAVAILABLE")
                    .counter().count()).isEqualTo(1.0);
        } finally {
            executorService.shutdownNow();
        }
    }

    @Test
    void doesNotRetryBadRequestOrCountItAsCircuitBreakerFailure() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        try {
            SupplierCallExecutor executor = executor(registry, executorService);
            AtomicInteger attempts = new AtomicInteger();

            assertThatThrownBy(() -> executor.execute("http", "gateway", () -> {
                attempts.incrementAndGet();
                throw new SupplierGatewayClientException(SupplierFailureCode.SUPPLIER_BAD_REQUEST, "bad request");
            })).isInstanceOfSatisfying(SupplierGatewayClientException.class,
                    exception -> assertThat(exception.failureCode()).isEqualTo(SupplierFailureCode.SUPPLIER_BAD_REQUEST));

            assertThat(attempts).hasValue(1);
            assertThat(registry.find("travelcare.supplier.retry.total").counters()).isEmpty();
            assertThat(executor.circuitBreakerState()).isEqualTo(CircuitBreaker.State.CLOSED);
        } finally {
            executorService.shutdownNow();
        }
    }

    @Test
    void doesNotRetryInvalidResponsesOrMissingFields() {
        for (SupplierFailureCode code : List.of(
                SupplierFailureCode.SUPPLIER_INVALID_RESPONSE,
                SupplierFailureCode.SUPPLIER_MISSING_FIELD)) {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            ExecutorService executorService = Executors.newSingleThreadExecutor();
            try {
                SupplierCallExecutor executor = executor(registry, executorService);
                AtomicInteger attempts = new AtomicInteger();

                assertThatThrownBy(() -> executor.execute("http", "gateway", () -> {
                    attempts.incrementAndGet();
                    throw new SupplierGatewayClientException(code, "invalid supplier response");
                })).isInstanceOfSatisfying(SupplierGatewayClientException.class,
                        exception -> assertThat(exception.failureCode()).isEqualTo(code));

                assertThat(attempts).hasValue(1);
                assertThat(registry.find("travelcare.supplier.retry.total").counters()).isEmpty();
            } finally {
                executorService.shutdownNow();
            }
        }
    }

    @Test
    void retainsTheLastRealFailureCodeWhenRetriesAreExhausted() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        try {
            SupplierCallExecutor executor = executor(registry, executorService);
            AtomicInteger attempts = new AtomicInteger();

            assertThatThrownBy(() -> executor.execute("http", "gateway", () -> {
                attempts.incrementAndGet();
                throw new SupplierGatewayClientException(SupplierFailureCode.SUPPLIER_CONNECTION_FAILED, "refused");
            })).isInstanceOfSatisfying(SupplierGatewayClientException.class,
                    exception -> assertThat(exception.failureCode()).isEqualTo(SupplierFailureCode.SUPPLIER_CONNECTION_FAILED));

            assertThat(attempts).hasValue(2);
        } finally {
            executorService.shutdownNow();
        }
    }

    @Test
    void mapsTimeLimitedAttemptToSupplierTimeout() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        try {
            SupplierCallExecutor executor = executor(registry, executorService);

            Instant startedAt = Instant.now();
            assertThatThrownBy(() -> executor.execute("http", "gateway", () -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return "late";
            })).isInstanceOfSatisfying(SupplierGatewayClientException.class,
                    exception -> assertThat(exception.failureCode()).isEqualTo(SupplierFailureCode.SUPPLIER_TIMEOUT));
            assertThat(Duration.between(startedAt, Instant.now())).isLessThan(Duration.ofMillis(300));
        } finally {
            executorService.shutdownNow();
        }
    }

    @Test
    void opensCircuitAfterTechnicalFailuresAndRejectsTheNextLogicalCall() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        try {
            SupplierCallExecutor executor = executor(registry, executorService);
            AtomicInteger attempts = new AtomicInteger();
            java.util.function.Supplier<String> unavailable = () -> {
                attempts.incrementAndGet();
                throw new SupplierGatewayClientException(SupplierFailureCode.SUPPLIER_UNAVAILABLE, "unavailable");
            };

            assertThatThrownBy(() -> executor.execute("http", "gateway", unavailable)).isInstanceOf(SupplierGatewayClientException.class);
            assertThatThrownBy(() -> executor.execute("http", "gateway", unavailable)).isInstanceOf(SupplierGatewayClientException.class);
            assertThat(executor.circuitBreakerState()).isEqualTo(CircuitBreaker.State.OPEN);

            assertThatThrownBy(() -> executor.execute("http", "gateway", unavailable))
                    .isInstanceOfSatisfying(SupplierGatewayClientException.class,
                            exception -> assertThat(exception.failureCode()).isEqualTo(SupplierFailureCode.SUPPLIER_CIRCUIT_OPEN));
            assertThat(attempts).hasValue(4);
        } finally {
            executorService.shutdownNow();
        }
    }

    @Test
    void businessNotFoundResultsDoNotOpenTheCircuit() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        try {
            SupplierCallExecutor executor = executor(registry, executorService);

            Optional<String> first = executor.execute("http", "gateway", Optional::<String>empty);
            Optional<String> second = executor.execute("http", "gateway", Optional::<String>empty);
            Optional<String> third = executor.execute("http", "gateway", Optional::<String>empty);
            assertThat(first).isEmpty();
            assertThat(second).isEmpty();
            assertThat(third).isEmpty();
            assertThat(executor.circuitBreakerState()).isEqualTo(CircuitBreaker.State.CLOSED);
        } finally {
            executorService.shutdownNow();
        }
    }

    private static SupplierCallExecutor executor(SimpleMeterRegistry registry, ExecutorService executorService) {
        CircuitBreaker circuitBreaker = CircuitBreaker.of("supplier", CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .recordException(exception -> exception instanceof SupplierGatewayClientException supplier
                        && supplier.failureCode().circuitBreakerFailure())
                .ignoreException(exception -> exception instanceof SupplierGatewayClientException supplier
                        && !supplier.failureCode().circuitBreakerFailure())
                .build());
        Retry retry = Retry.of("supplier", RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ZERO)
                .retryOnException(exception -> exception instanceof SupplierGatewayClientException supplier
                        && supplier.failureCode().retryable())
                .build());
        TimeLimiter timeLimiter = TimeLimiter.of(TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(50))
                .cancelRunningFuture(true)
                .build());
        return new SupplierCallExecutor(circuitBreaker, retry, timeLimiter, executorService, new TravelCareMetrics(registry));
    }
}
