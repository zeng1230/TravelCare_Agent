package travelcare_agent.adapter.order;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import travelcare_agent.observability.TravelCareMetrics;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SupplierGatewayHealthIndicatorTest {

    @Test
    void returnsUpWhenGatewayHealthStatusIsUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://supplier.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SupplierGatewayHealthIndicator indicator = new SupplierGatewayHealthIndicator(builder.build(), "supplier.test");
        server.expect(requestTo("http://supplier.test/actuator/health"))
                .andRespond(withSuccess("{\"status\":\"UP\"}", MediaType.APPLICATION_JSON));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        server.verify();
    }

    @Test
    void returnsDownWithLowSensitivityDetailsWhenGatewayIsDown() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://supplier.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SupplierGatewayHealthIndicator indicator = new SupplierGatewayHealthIndicator(builder.build(), "supplier.test");
        server.expect(requestTo("http://supplier.test/actuator/health"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":\"DOWN\",\"details\":{\"url\":\"http://supplier.test/internal\"}}"));

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("supplier", "gateway");
        assertThat(health.getDetails()).containsKey("errorType");
        assertThat(health.getDetails().toString()).doesNotContain("http://supplier.test/internal");
        server.verify();
    }

    @Test
    void healthCheckDoesNotRecordSupplierBusinessMetrics() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://supplier.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SupplierGatewayHealthIndicator indicator = new SupplierGatewayHealthIndicator(builder.build(), "supplier.test");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TravelCareMetrics metrics = new TravelCareMetrics(registry);
        server.expect(requestTo("http://supplier.test/actuator/health"))
                .andRespond(withSuccess("{\"status\":\"UP\"}", MediaType.APPLICATION_JSON));

        indicator.health();
        metrics.timer("travelcare.unrelated.latency", Duration.ofMillis(1), java.util.Map.of("status", "ok"));

        assertThat(registry.find("travelcare.supplier.requests.total").counters()).isEmpty();
        assertThat(registry.find("travelcare.supplier.failures.total").counters()).isEmpty();
        server.verify();
    }
}
