package travelcare_agent.adapter.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import travelcare_agent.common.trace.TraceIdFilter;
import travelcare_agent.trace.TraceContextHolder;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

@Component
@ConditionalOnProperty(name = "travelcare.supplier.mode", havingValue = "http")
public class HttpSupplierOrderAdapter implements OrderAdapter {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    private final RestClient restClient;
    private final SupplierGatewayProperties properties;
    private final ObjectMapper objectMapper;

    @Autowired
    public HttpSupplierOrderAdapter(RestClient.Builder builder, SupplierGatewayProperties properties) {
        this(builder.requestFactory(requestFactory(properties)).build(), properties);
    }

    HttpSupplierOrderAdapter(RestClient restClient, SupplierGatewayProperties properties) {
        this.properties = properties;
        this.restClient = restClient;
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    @Override
    public Optional<OrderSnapshot> getOrder(Long orderId, String orderNo, Long userId) {
        travelcare_agent.dryrun.SideEffectGuard.checkCurrent(travelcare_agent.dryrun.SideEffectOperation.EXTERNAL_ADAPTER_CALL);
        String effectiveOrderNo = orderNo;
        if ((effectiveOrderNo == null || effectiveOrderNo.isBlank()) && orderId != null) {
            effectiveOrderNo = "ORD-" + orderId;
        }
        if (effectiveOrderNo == null || effectiveOrderNo.isBlank()) {
            throw new SupplierGatewayClientException("INVALID_REQUEST", "orderNo is required for supplier gateway lookup");
        }

        String url = UriComponentsBuilder.fromHttpUrl(properties.getBaseUrl())
                .path("/supplier/orders/{orderNo}")
                .queryParam("userId", userId)
                .buildAndExpand(effectiveOrderNo)
                .toUriString();
        try {
            String body = restClient.get()
                    .uri(url)
                    .header(TRACE_ID_HEADER, currentTraceId())
                    .retrieve()
                    .body(String.class);
            OrderSnapshot snapshot = parseAndValidate(body);
            return Optional.of(snapshot);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            String code = supplierErrorCode(ex.getResponseBodyAsString(), "SUPPLIER_HTTP_" + ex.getStatusCode().value());
            throw new SupplierGatewayClientException(code, "supplier gateway returned " + ex.getStatusCode(), ex);
        } catch (RestClientException ex) {
            if (isTimeout(ex)) {
                throw new SupplierGatewayClientException("SUPPLIER_TIMEOUT", "supplier gateway timeout", ex);
            }
            throw new SupplierGatewayClientException("SUPPLIER_CALL_FAILED", "supplier gateway call failed", ex);
        }
    }

    private OrderSnapshot parseAndValidate(String body) {
        try {
            OrderSnapshot snapshot = objectMapper.readValue(body, OrderSnapshot.class);
            if (snapshot.orderId() == null || isBlank(snapshot.orderNo()) || snapshot.userId() == null
                    || snapshot.status() == null || snapshot.paidAmount() == null || snapshot.departureTime() == null) {
                throw new SupplierGatewayClientException("SUPPLIER_MISSING_FIELD", "supplier response is missing required order fields");
            }
            return snapshot;
        } catch (SupplierGatewayClientException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new SupplierGatewayClientException("SUPPLIER_MALFORMED_RESPONSE", "supplier response is malformed", ex);
        }
    }

    private String supplierErrorCode(String body, String fallback) {
        try {
            String code = objectMapper.readTree(body).path("code").asText();
            return code == null || code.isBlank() ? fallback : code;
        } catch (Exception ex) {
            return fallback;
        }
    }

    private String currentTraceId() {
        String traceId = TraceIdFilter.currentTraceId();
        if (!isBlank(traceId)) {
            return traceId;
        }
        TraceContextHolder.TraceContext context = TraceContextHolder.current();
        if (context != null && !isBlank(context.traceId())) {
            return context.traceId();
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static SimpleClientHttpRequestFactory requestFactory(SupplierGatewayProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(toMillis(properties.getConnectTimeout()));
        factory.setReadTimeout(toMillis(properties.getReadTimeout()));
        return factory;
    }

    private static int toMillis(Duration duration) {
        return Math.toIntExact((duration == null ? Duration.ofSeconds(1) : duration).toMillis());
    }

    private static boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (current instanceof TimeoutException
                    || current instanceof SocketTimeoutException
                    || (message != null && message.toLowerCase(java.util.Locale.ROOT).contains("timeout"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
