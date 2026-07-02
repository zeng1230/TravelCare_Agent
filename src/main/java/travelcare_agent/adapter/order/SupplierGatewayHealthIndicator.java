package travelcare_agent.adapter.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import travelcare_agent.common.trace.TraceIdFilter;

import java.net.URI;

@Component
@ConditionalOnProperty(name = "travelcare.supplier.mode", havingValue = "http")
public class SupplierGatewayHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(SupplierGatewayHealthIndicator.class);
    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final int HEALTH_TIMEOUT_MS = 1000;
    private static final String SUPPLIER = "gateway";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String host;

    @Autowired
    public SupplierGatewayHealthIndicator(RestClient.Builder builder, SupplierGatewayProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(HEALTH_TIMEOUT_MS);
        factory.setReadTimeout(HEALTH_TIMEOUT_MS);
        RestClient client = builder
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory)
                .build();
        this.restClient = client;
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
        this.host = host(properties.getBaseUrl());
    }

    SupplierGatewayHealthIndicator(RestClient restClient, String host) {
        this.restClient = restClient;
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
        this.host = host;
    }

    @Override
    public Health health() {
        String traceId = TraceIdFilter.currentTraceId();
        try {
            String body = restClient.get()
                    .uri("/actuator/health")
                    .header(TRACE_ID_HEADER, traceId == null ? "" : traceId)
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(body);
            String status = node.path("status").asText();
            if ("UP".equalsIgnoreCase(status)) {
                return Health.up().withDetail("supplier", SUPPLIER).build();
            }
            log.warn("supplier gateway health down supplier={} host={} traceId={} errorType={}",
                    SUPPLIER, host, traceId, "NonUpStatus");
            return Health.down()
                    .withDetail("supplier", SUPPLIER)
                    .withDetail("errorType", "NonUpStatus")
                    .build();
        } catch (Exception ex) {
            log.warn("supplier gateway health down supplier={} host={} traceId={} errorType={}",
                    SUPPLIER, host, traceId, ex.getClass().getSimpleName());
            return Health.down()
                    .withDetail("supplier", SUPPLIER)
                    .withDetail("errorType", ex.getClass().getSimpleName())
                    .build();
        }
    }

    private static String host(String baseUrl) {
        try {
            return URI.create(baseUrl).getHost();
        } catch (RuntimeException ex) {
            return "unknown";
        }
    }
}
