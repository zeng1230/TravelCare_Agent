package travelcare_agent.adapter.order;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import travelcare_agent.observability.TravelCareMetrics;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

public class SupplierCallExecutor {

    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final TimeLimiter timeLimiter;
    private final ExecutorService executorService;
    private final TravelCareMetrics metrics;

    public SupplierCallExecutor(CircuitBreaker circuitBreaker, Retry retry, TimeLimiter timeLimiter,
                                ExecutorService executorService, TravelCareMetrics metrics) {
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
        this.timeLimiter = timeLimiter;
        this.executorService = executorService;
        this.metrics = metrics;
        retry.getEventPublisher().onRetry(event -> recordRetry(event.getLastThrowable()));
    }

    public <T> T execute(String adapter, String supplier, Supplier<T> remoteCall) {
        Supplier<T> timedAttempt = () -> executeTimeLimited(remoteCall);
        Supplier<T> retryableCall = Retry.decorateSupplier(retry, timedAttempt);
        try {
            return CircuitBreaker.decorateSupplier(circuitBreaker, retryableCall).get();
        } catch (CallNotPermittedException exception) {
            throw new SupplierGatewayClientException(SupplierFailureCode.SUPPLIER_CIRCUIT_OPEN,
                    "supplier circuit breaker is open", exception);
        }
    }

    CircuitBreaker.State circuitBreakerState() {
        return circuitBreaker.getState();
    }

    private <T> T executeTimeLimited(Supplier<T> remoteCall) {
        try {
            // Cancellation requests interruption, but the HTTP socket is ultimately bounded by RestClient timeouts.
            return timeLimiter.executeFutureSupplier(() -> executorService.submit(remoteCall::get));
        } catch (TimeoutException exception) {
            throw new SupplierGatewayClientException(SupplierFailureCode.SUPPLIER_TIMEOUT,
                    "supplier call exceeded time limit", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SupplierGatewayClientException(SupplierFailureCode.SUPPLIER_TIMEOUT,
                    "supplier call interrupted while waiting", exception);
        } catch (ExecutionException exception) {
            throw propagate(exception.getCause());
        } catch (RejectedExecutionException exception) {
            throw new SupplierGatewayClientException(SupplierFailureCode.SUPPLIER_UNAVAILABLE,
                    "supplier execution capacity is unavailable", exception);
        } catch (Exception exception) {
            throw propagate(exception);
        }
    }

    private RuntimeException propagate(Throwable throwable) {
        if (throwable instanceof SupplierGatewayClientException supplierException) {
            return supplierException;
        }
        if (throwable instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new SupplierGatewayClientException(SupplierFailureCode.SUPPLIER_UNAVAILABLE,
                "supplier call failed", throwable);
    }

    private void recordRetry(Throwable throwable) {
        if (metrics == null || !(throwable instanceof SupplierGatewayClientException supplierException)) {
            return;
        }
        metrics.recordSupplierRetry("http", "gateway", supplierException.failureCode().name());
    }
}
