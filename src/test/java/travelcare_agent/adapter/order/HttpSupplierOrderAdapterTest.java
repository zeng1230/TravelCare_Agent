package travelcare_agent.adapter.order;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import travelcare_agent.enums.OrderStatus;

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
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpSupplierOrderAdapterTest {

    @Test
    void mapsRemoteSuccessToOrderSnapshotAndPropagatesTraceId() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpSupplierOrderAdapter adapter = adapter(builder);
        server.expect(requestTo("http://supplier.test/supplier/orders/ORD-1001?userId=1001"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Trace-Id", org.hamcrest.Matchers.notNullValue()))
                .andRespond(withSuccess("""
                        {
                          "orderId":1001,
                          "orderNo":"ORD-1001",
                          "userId":1001,
                          "status":"PAID",
                          "refundable":true,
                          "paidAmount":100.00,
                          "departureTime":"2026-05-13T10:00:00"
                        }
                        """, MediaType.APPLICATION_JSON));

        Optional<OrderSnapshot> order = adapter.getOrder(null, "ORD-1001", 1001L);

        assertThat(order).isPresent();
        assertThat(order.get().status()).isEqualTo(OrderStatus.PAID);
        assertThat(order.get().refundable()).isTrue();
        server.verify();
    }

    @Test
    void mapsRemoteNotFoundToEmpty() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpSupplierOrderAdapter adapter = adapter(builder);
        server.expect(requestTo("http://supplier.test/supplier/orders/ORD-404?userId=1001"))
                .andRespond(withResourceNotFound()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(error("ORDER_NOT_FOUND")));

        Optional<OrderSnapshot> order = adapter.getOrder(null, "ORD-404", 1001L);

        assertThat(order).isEmpty();
        server.verify();
    }

    @Test
    void mapsRemoteBadRequestToSupplierFailureNotEmpty() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpSupplierOrderAdapter adapter = adapter(builder);
        server.expect(requestTo("http://supplier.test/supplier/orders/ORD-1001?userId=1001"))
                .andRespond(withBadRequest()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(error("INVALID_REQUEST")));

        assertThatThrownBy(() -> adapter.getOrder(null, "ORD-1001", 1001L))
                .isInstanceOf(SupplierGatewayClientException.class)
                .hasMessageContaining("INVALID_REQUEST");
        server.verify();
    }

    @Test
    void mapsRemoteServerErrorToSupplierFailureNotEmpty() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpSupplierOrderAdapter adapter = adapter(builder);
        server.expect(requestTo("http://supplier.test/supplier/orders/ORD-1001?userId=1001"))
                .andRespond(withServerError()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(error("SUPPLIER_INTERNAL_ERROR")));

        assertThatThrownBy(() -> adapter.getOrder(null, "ORD-1001", 1001L))
                .isInstanceOf(SupplierGatewayClientException.class)
                .hasMessageContaining("SUPPLIER_INTERNAL_ERROR");
        server.verify();
    }

    @Test
    void mapsMalformedResponseToSupplierFailureNotEmpty() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpSupplierOrderAdapter adapter = adapter(builder);
        server.expect(requestTo("http://supplier.test/supplier/orders/ORD-1001?userId=1001"))
                .andRespond(withSuccess("{", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.getOrder(null, "ORD-1001", 1001L))
                .isInstanceOf(SupplierGatewayClientException.class)
                .hasMessageContaining("SUPPLIER_MALFORMED_RESPONSE");
        server.verify();
    }

    @Test
    void mapsMissingRequiredFieldToSupplierFailureNotEmpty() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpSupplierOrderAdapter adapter = adapter(builder);
        server.expect(requestTo("http://supplier.test/supplier/orders/ORD-1001?userId=1001"))
                .andRespond(withSuccess("""
                        {
                          "orderId":1001,
                          "orderNo":"ORD-1001",
                          "userId":1001,
                          "refundable":true,
                          "paidAmount":100.00,
                          "departureTime":"2026-05-13T10:00:00"
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.getOrder(null, "ORD-1001", 1001L))
                .isInstanceOf(SupplierGatewayClientException.class)
                .hasMessageContaining("SUPPLIER_MISSING_FIELD");
        server.verify();
    }

    private static HttpSupplierOrderAdapter adapter(RestClient.Builder builder) {
        SupplierGatewayProperties properties = new SupplierGatewayProperties();
        properties.setBaseUrl("http://supplier.test");
        properties.setConnectTimeout(Duration.ofMillis(100));
        properties.setReadTimeout(Duration.ofMillis(100));
        return new HttpSupplierOrderAdapter(builder.build(), properties);
    }

    private static String error(String code) {
        return "{\"code\":\"" + code + "\",\"message\":\"" + code + "\",\"traceId\":\"trace-test\"}";
    }
}
