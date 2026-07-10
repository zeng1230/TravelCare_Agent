package travelcare_agent.adapter.order;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import travelcare_agent.observability.TravelCareMetrics;

import java.util.concurrent.ExecutorService;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;

import static org.assertj.core.api.Assertions.assertThat;

class SupplierOrderAdapterConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AdapterTestConfiguration.class);

    @Test
    void httpModeCreatesHttpSupplierOrderAdapter() {
        contextRunner
                .withPropertyValues(
                        "travelcare.supplier.mode=http",
                        "travelcare.supplier.gateway.base-url=http://localhost:8081"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OrderAdapter.class);
                    assertThat(context).hasSingleBean(HttpSupplierOrderAdapter.class);
                    assertThat(context).hasSingleBean(SupplierGatewayHealthIndicator.class);
                    assertThat(context).hasSingleBean(SupplierCallExecutor.class);
                    assertThat(context).hasSingleBean(SupplierResilienceProperties.class);
                    assertThat(context).hasSingleBean(CircuitBreaker.class);
                    assertThat(context).hasSingleBean(Retry.class);
                    assertThat(context).hasSingleBean(TimeLimiter.class);
                    assertThat(context).hasBean("supplierResilienceExecutor");
                    assertThat(context.getBean(CircuitBreaker.class).getCircuitBreakerConfig()
                            .getSlowCallDurationThreshold()).isEqualTo(java.time.Duration.ofMillis(3500));
                    assertThat(context.getBean(TimeLimiter.class).getTimeLimiterConfig()
                            .getTimeoutDuration()).isEqualTo(java.time.Duration.ofMillis(1500));
                    assertThat(context.getBean(SupplierGatewayProperties.class).getReadTimeout())
                            .isLessThanOrEqualTo(context.getBean(TimeLimiter.class).getTimeLimiterConfig()
                                    .getTimeoutDuration());
                    assertThat(context).doesNotHaveBean(MockOrderAdapter.class);
                    assertThat(context.getBean(OrderAdapter.class)).isInstanceOf(HttpSupplierOrderAdapter.class);
                });
    }

    @Test
    void defaultModeCreatesMockOrderAdapter() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OrderAdapter.class);
            assertThat(context).hasSingleBean(MockOrderAdapter.class);
            assertThat(context).doesNotHaveBean(HttpSupplierOrderAdapter.class);
            assertThat(context).doesNotHaveBean(SupplierGatewayHealthIndicator.class);
            assertThat(context).doesNotHaveBean(SupplierCallExecutor.class);
            assertThat(context).doesNotHaveBean(SupplierResilienceProperties.class);
            assertThat(context).doesNotHaveBean(CircuitBreaker.class);
            assertThat(context).doesNotHaveBean(Retry.class);
            assertThat(context).doesNotHaveBean(TimeLimiter.class);
            assertThat(context).doesNotHaveBean("supplierResilienceExecutor");
            assertThat(context.getBean(OrderAdapter.class)).isInstanceOf(MockOrderAdapter.class);
        });
    }

    @Configuration
    @Import({HttpSupplierOrderAdapter.class, MockOrderAdapter.class, SupplierGatewayProperties.class,
            SupplierGatewayHealthIndicator.class, SupplierResilienceConfiguration.class})
    static class AdapterTestConfiguration {
        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }

        @Bean
        TravelCareMetrics travelCareMetrics() {
            return new TravelCareMetrics(new SimpleMeterRegistry());
        }
    }
}
