package travelcare_agent.adapter.order;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import travelcare_agent.enums.OrderStatus;
import travelcare_agent.observability.TravelCareMetrics;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpSupplierOrderAdapterTest {

    @Test
    void mapsRemoteSuccessToOrderSnapshotPropagatesTraceIdAndRecordsMetrics() {
        Fixture fixture = mockServerFixture();
        fixture.server.expect(requestTo("http://supplier.test/supplier/orders/ORD-1001?userId=1001"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Trace-Id", org.hamcrest.Matchers.notNullValue()))
                .andRespond(withSuccess(orderJson(), MediaType.APPLICATION_JSON));

        Optional<OrderSnapshot> order = fixture.adapter.getOrder(null, "ORD-1001", 1001L);

        assertThat(order).isPresent();
        assertThat(order.get().status()).isEqualTo(OrderStatus.PAID);
        assertThat(order.get().refundable()).isTrue();
        assertCounter(fixture.registry, "travelcare.supplier.requests.total", "success", 1.0);
        assertThat(fixture.registry.find("travelcare.supplier.failures.total").counters()).isEmpty();
        fixture.server.verify();
    }

    @Test
    void mapsRemoteNotFoundToEmptyAndDoesNotRecordFailureMetric() {
        Fixture fixture = mockServerFixture();
        fixture.server.expect(requestTo("http://supplier.test/supplier/orders/ORD-404?userId=1001"))
                .andRespond(withResourceNotFound()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(error("ORDER_NOT_FOUND")));

        Optional<OrderSnapshot> order = fixture.adapter.getOrder(null, "ORD-404", 1001L);

        assertThat(order).isEmpty();
        assertCounter(fixture.registry, "travelcare.supplier.requests.total", "not_found", 1.0);
        assertThat(fixture.registry.find("travelcare.supplier.failures.total").counters()).isEmpty();
        fixture.server.verify();
    }

    @Test
    void mapsGatewayTimeoutStatusToSupplierTimeout() {
        Fixture fixture = mockServerFixture();
        fixture.server.expect(requestTo("http://supplier.test/supplier/orders/ORD-1001?userId=1001"))
                .andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(error("SUPPLIER_TIMEOUT")));

        assertSupplierFailure(fixture, SupplierFailureCode.SUPPLIER_TIMEOUT, "timeout");
    }

    @Test
    void mapsClientTimeoutToSupplierTimeout() {
        Fixture fixture = transportFailureFixture(new ResourceAccessException(
                "I/O error", new SocketTimeoutException("Read timed out")));

        assertSupplierFailure(fixture, SupplierFailureCode.SUPPLIER_TIMEOUT, "timeout");
    }

    @Test
    void mapsRemoteServerErrorToUnavailable() {
        Fixture fixture = mockServerFixture();
        fixture.server.expect(requestTo("http://supplier.test/supplier/orders/ORD-1001?userId=1001"))
                .andRespond(withServerError()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(error("SUPPLIER_INTERNAL_ERROR")));

        assertSupplierFailure(fixture, SupplierFailureCode.SUPPLIER_UNAVAILABLE, "unavailable");
    }

    @Test
    void mapsMalformedResponseToInvalidResponse() {
        Fixture fixture = mockServerFixture();
        fixture.server.expect(requestTo("http://supplier.test/supplier/orders/ORD-1001?userId=1001"))
                .andRespond(withSuccess("{", MediaType.APPLICATION_JSON));

        assertSupplierFailure(fixture, SupplierFailureCode.SUPPLIER_INVALID_RESPONSE, "invalid_response");
    }

    @Test
    void mapsTypeMismatchToInvalidResponse() {
        Fixture fixture = mockServerFixture();
        fixture.server.expect(requestTo("http://supplier.test/supplier/orders/ORD-1001?userId=1001"))
                .andRespond(withSuccess("""
                        {
                          "orderId":1001,
                          "orderNo":"ORD-1001",
                          "userId":1001,
                          "status":"PAID",
                          "refundable":"not-a-boolean",
                          "paidAmount":100.00,
                          "departureTime":"2026-05-13T10:00:00"
                        }
                        """, MediaType.APPLICATION_JSON));

        assertSupplierFailure(fixture, SupplierFailureCode.SUPPLIER_INVALID_RESPONSE, "invalid_response");
    }

    @Test
    void mapsMissingRequiredFieldToMissingFieldBeforeDeserializationDefaultsPrimitiveBoolean() {
        Fixture fixture = mockServerFixture();
        fixture.server.expect(requestTo("http://supplier.test/supplier/orders/ORD-1001?userId=1001"))
                .andRespond(withSuccess("""
                        {
                          "orderId":1001,
                          "orderNo":"ORD-1001",
                          "userId":1001,
                          "status":"PAID",
                          "paidAmount":100.00,
                          "departureTime":"2026-05-13T10:00:00"
                        }
                        """, MediaType.APPLICATION_JSON));

        assertSupplierFailure(fixture, SupplierFailureCode.SUPPLIER_MISSING_FIELD, "invalid_response");
    }

    @Test
    void mapsConnectionFailureToConnectionFailed() {
        Fixture fixture = transportFailureFixture(new ResourceAccessException("Connection refused"));

        assertSupplierFailure(fixture, SupplierFailureCode.SUPPLIER_CONNECTION_FAILED, "connection_failed");
    }

    @Test
    void mapsBadRequestToBadRequest() {
        Fixture fixture = mockServerFixture();
        fixture.server.expect(requestTo("http://supplier.test/supplier/orders/ORD-1001?userId=1001"))
                .andRespond(withBadRequest()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(error("INVALID_REQUEST")));

        assertSupplierFailure(fixture, SupplierFailureCode.SUPPLIER_BAD_REQUEST, "bad_request");
    }

    @Test
    void exceptionMessageDoesNotExposeSensitiveValuesFullUrlOrRawBody() {
        Fixture fixture = mockServerFixture();
        fixture.server.expect(requestTo("http://supplier.test/supplier/orders/ORD-SECRET-123?userId=1001"))
                .andRespond(withServerError()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"Authorization\":\"Bearer secret-token\",\"apiKey\":\"secret\",\"orderNo\":\"ORD-SECRET-123\"}"));

        assertThatThrownBy(() -> fixture.adapter.getOrder(null, "ORD-SECRET-123", 1001L))
                .isInstanceOf(SupplierGatewayClientException.class)
                .hasMessageContaining("SUPPLIER_UNAVAILABLE")
                .satisfies(error -> {
                    assertThat(error.getMessage()).doesNotContain("Authorization", "Bearer", "apiKey",
                            "secret-token", "http://supplier.test", "/supplier/orders", "ORD-SECRET-123");
                    assertThat(error.getMessage()).contains("orderHash=");
                });
    }

    private static void assertSupplierFailure(Fixture fixture, SupplierFailureCode code, String outcome) {
        assertThatThrownBy(() -> fixture.adapter.getOrder(null, "ORD-1001", 1001L))
                .isInstanceOfSatisfying(SupplierGatewayClientException.class, ex -> {
                    assertThat(ex.failureCode()).isEqualTo(code);
                    assertThat(ex.errorCode()).isEqualTo(code.name());
                });
        assertCounter(fixture.registry, "travelcare.supplier.requests.total", outcome, 1.0);
        assertCounter(fixture.registry, "travelcare.supplier.failures.total", outcome, 1.0);
        if (fixture.server != null) {
            fixture.server.verify();
        }
    }

    private static Fixture mockServerFixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HttpSupplierOrderAdapter adapter = adapter(builder.build(), registry);
        return new Fixture(adapter, server, registry);
    }

    private static Fixture transportFailureFixture(RuntimeException failure) {
        RestClient restClient = RestClient.builder()
                .requestFactory((uri, httpMethod) -> {
                    throw failure;
                })
                .build();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        return new Fixture(adapter(restClient, registry), null, registry);
    }

    private static HttpSupplierOrderAdapter adapter(RestClient restClient, SimpleMeterRegistry registry) {
        SupplierGatewayProperties properties = new SupplierGatewayProperties();
        properties.setBaseUrl("http://supplier.test");
        properties.setConnectTimeout(Duration.ofMillis(100));
        properties.setReadTimeout(Duration.ofMillis(100));
        return new HttpSupplierOrderAdapter(restClient, properties, new TravelCareMetrics(registry));
    }

    private static void assertCounter(SimpleMeterRegistry registry, String name, String outcome, double count) {
        assertThat(registry.get(name).tag("adapter", "http").tag("supplier", "gateway").tag("outcome", outcome)
                .counter().count()).isEqualTo(count);
    }

    private static String orderJson() {
        return """
                {
                  "orderId":1001,
                  "orderNo":"ORD-1001",
                  "userId":1001,
                  "status":"PAID",
                  "refundable":true,
                  "paidAmount":100.00,
                  "departureTime":"2026-05-13T10:00:00"
                }
                """;
    }

    private static String error(String code) {
        return "{\"code\":\"" + code + "\",\"message\":\"" + code + "\",\"traceId\":\"trace-test\"}";
    }

    private record Fixture(HttpSupplierOrderAdapter adapter, MockRestServiceServer server, SimpleMeterRegistry registry) {
    }
}
