package travelcare_agent.observability;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import travelcare_agent.common.exception.GlobalExceptionHandler;
import travelcare_agent.security.JwtAuthenticationFilter;
import travelcare_agent.security.JwtTokenService;
import travelcare_agent.security.SecurityConfig;
import travelcare_agent.security.SecurityContextFacade;
import travelcare_agent.security.SecurityTestTokenFactory;
import travelcare_agent.trace.RedactionService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = ActuatorSecurityWebMvcTest.TestApp.class)
@AutoConfigureMockMvc
@Import({
        GlobalExceptionHandler.class,
        SecurityConfig.class,
        JwtTokenService.class,
        JwtAuthenticationFilter.class,
        SecurityContextFacade.class,
        RedactionService.class,
        ObservabilityActuatorConfiguration.class,
        MetricsAutoConfiguration.class,
        MetricsEndpointAutoConfiguration.class,
        HealthEndpointAutoConfiguration.class,
        WebEndpointAutoConfiguration.class
})
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "travelcare.jwt.secret=" + SecurityTestTokenFactory.SECRET,
        "travelcare.jwt.issuer=travelcare-agent",
        "management.endpoints.web.exposure.include=health,metrics",
        "management.endpoint.metrics.enabled=true"
})
class ActuatorSecurityWebMvcTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private WebEndpointsSupplier webEndpointsSupplier;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
        @Bean
        SimpleMeterRegistry meterRegistry() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            registry.counter("travelcare.test.visible.total").increment();
            return registry;
        }
    }

    @Test
    void healthIsPublicButMetricsRequireAdmin() throws Exception {
        assertThat(webEndpointsSupplier.getEndpoints())
                .extracting(endpoint -> endpoint.getEndpointId().toString())
                .contains("metrics");

        mvc.perform(get("/actuator/health")).andExpect(status().isOk());

        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/metrics/jvm.memory.used")).andExpect(status().isUnauthorized());

        String user = SecurityTestTokenFactory.bearer(1001L, "tenant-a", "USER");
        mvc.perform(get("/actuator/metrics").header("Authorization", user)).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/metrics/jvm.memory.used").header("Authorization", user)).andExpect(status().isForbidden());

        String admin = SecurityTestTokenFactory.bearer(1L, "tenant-a", "ADMIN");
        mvc.perform(get("/actuator/metrics").header("Authorization", admin)).andExpect(status().isOk());
        mvc.perform(get("/actuator/metrics/jvm.memory.used").header("Authorization", admin)).andExpect(status().isOk());
    }
}
