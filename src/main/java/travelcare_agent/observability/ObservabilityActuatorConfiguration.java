package travelcare_agent.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.metrics.MetricsEndpoint;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ObservabilityActuatorConfiguration {

    @Bean
    @ConditionalOnMissingBean
    MetricsEndpoint metricsEndpoint(MeterRegistry registry) {
        return new MetricsEndpoint(registry);
    }
}
