package travelcare_agent.adapter.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import travelcare_agent.common.trace.TraceIdFilter;
import travelcare_agent.observability.TravelCareMetrics;
import travelcare_agent.trace.TraceContextHolder;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

@Component
@ConditionalOnProperty(name = "travelcare.supplier.mode", havingValue = "http")
public class HttpSupplierOrderAdapter implements OrderAdapter {

    private static final Logger log = LoggerFactory.getLogger(HttpSupplierOrderAdapter.class);
    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String ADAPTER = "http";
    private static final String SUPPLIER = "gateway";

    private final RestClient restClient;
    private final SupplierGatewayProperties properties;
    private final SupplierCallExecutor supplierCallExecutor;
    private final ObjectMapper objectMapper;
    private final TravelCareMetrics metrics;

    @Autowired
    public HttpSupplierOrderAdapter(RestClient.Builder builder, SupplierGatewayProperties properties,
                                    SupplierCallExecutor supplierCallExecutor,
                                    @Autowired(required = false) TravelCareMetrics metrics) {
        this(builder.requestFactory(requestFactory(properties)).build(), properties, supplierCallExecutor, metrics);
    }

    HttpSupplierOrderAdapter(RestClient restClient, SupplierGatewayProperties properties,
                             SupplierCallExecutor supplierCallExecutor, TravelCareMetrics metrics) {
        this.properties = properties;
        this.restClient = restClient;
        this.supplierCallExecutor = supplierCallExecutor;
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
        this.metrics = metrics;
    }

    @Override
    public Optional<OrderSnapshot> getOrder(Long orderId, String orderNo, Long userId) {
        travelcare_agent.dryrun.SideEffectGuard.checkCurrent(travelcare_agent.dryrun.SideEffectOperation.EXTERNAL_ADAPTER_CALL);
        String effectiveOrderNo = orderNo;
        if ((effectiveOrderNo == null || effectiveOrderNo.isBlank()) && orderId != null) {
            effectiveOrderNo = "ORD-" + orderId;
        }
        if (effectiveOrderNo == null || effectiveOrderNo.isBlank()) {
            throw new SupplierGatewayClientException(SupplierFailureCode.SUPPLIER_BAD_REQUEST,
                    "supplier gateway lookup requires order reference");
        }

        String url = UriComponentsBuilder.fromHttpUrl(properties.getBaseUrl())
                .path("/supplier/orders/{orderNo}")
                .queryParam("userId", userId)
                .buildAndExpand(effectiveOrderNo)
                .toUriString();
        Instant startedAt = Instant.now();
        SupplierFailureCode outcome = null;
        String host = host(properties.getBaseUrl());
        String orderHash = orderHash(effectiveOrderNo);
        String traceId = currentTraceId();
        try {
            log.info("supplier order request traceId={} adapterName={} supplier={} host={} orderHash={}",
                    traceId, getClass().getSimpleName(), SUPPLIER, host, orderHash);
            Optional<OrderSnapshot> result = supplierCallExecutor.execute(ADAPTER, SUPPLIER,
                    () -> executeRemoteLookup(url, traceId, host, orderHash));
            if (result.isEmpty()) {
                outcome = SupplierFailureCode.SUPPLIER_ORDER_NOT_FOUND;
            }
            log.info("supplier order response traceId={} adapterName={} supplier={} host={} orderHash={} outcome={}",
                    traceId, getClass().getSimpleName(), SUPPLIER, host, orderHash,
                    result.isEmpty() ? "not_found" : "success");
            return result;
        } catch (SupplierGatewayClientException ex) {
            outcome = ex.failureCode();
            log.warn("supplier order failure traceId={} adapterName={} supplier={} host={} orderHash={} failureCode={}",
                    traceId, getClass().getSimpleName(), SUPPLIER, host, orderHash, outcome.name());
            throw ex;
        } finally {
            if (metrics != null) {
                String metricOutcome = outcome == null ? "success" : outcome.outcome();
                metrics.recordSupplierCall(ADAPTER, SUPPLIER, metricOutcome, Duration.between(startedAt, Instant.now()), outcome);
            }
        }
    }

    private Optional<OrderSnapshot> executeRemoteLookup(String url, String traceId, String host, String orderHash) {
        try {
            String body = restClient.get()
                    .uri(url)
                    .header(TRACE_ID_HEADER, traceId)
                    .retrieve()
                    .body(String.class);
            return Optional.of(parseAndValidate(body));
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            SupplierFailureCode code = httpFailureCode(ex);
            throw new SupplierGatewayClientException(code, "supplier gateway returned status=" + ex.getStatusCode().value()
                    + " supplier=" + SUPPLIER + " host=" + host + " orderHash=" + orderHash, ex);
        } catch (RestClientException ex) {
            SupplierFailureCode code = isTimeout(ex)
                    ? SupplierFailureCode.SUPPLIER_TIMEOUT
                    : SupplierFailureCode.SUPPLIER_CONNECTION_FAILED;
            throw new SupplierGatewayClientException(code, "supplier gateway call failed supplier=" + SUPPLIER
                    + " host=" + host + " orderHash=" + orderHash, ex);
        }
    }

    private OrderSnapshot parseAndValidate(String body) {
        JsonNode node;
        try {
            node = objectMapper.readTree(body);
        } catch (JsonProcessingException ex) {
            throw new SupplierGatewayClientException(SupplierFailureCode.SUPPLIER_INVALID_RESPONSE,
                    "supplier response is invalid JSON", ex);
        }
        if (node == null || !node.isObject()) {
            throw new SupplierGatewayClientException(SupplierFailureCode.SUPPLIER_INVALID_RESPONSE,
                    "supplier response has invalid structure");
        }
        requireField(node, "orderNo");
        requireField(node, "userId");
        requireField(node, "status");
        requireField(node, "refundable");
        requireField(node, "paidAmount");
        requireField(node, "departureTime");
        try {
            return objectMapper.treeToValue(node, OrderSnapshot.class);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw new SupplierGatewayClientException(SupplierFailureCode.SUPPLIER_INVALID_RESPONSE,
                    "supplier response cannot be mapped to order snapshot", ex);
        }
    }

    private static void requireField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || (value.isTextual() && value.asText().isBlank())) {
            throw new SupplierGatewayClientException(SupplierFailureCode.SUPPLIER_MISSING_FIELD,
                    "supplier response is missing required order field=" + field);
        }
    }

    private static SupplierFailureCode httpFailureCode(RestClientResponseException ex) {
        if (ex.getStatusCode() == HttpStatus.GATEWAY_TIMEOUT) {
            return SupplierFailureCode.SUPPLIER_TIMEOUT;
        }
        if (ex.getStatusCode().is5xxServerError()) {
            return SupplierFailureCode.SUPPLIER_UNAVAILABLE;
        }
        return SupplierFailureCode.SUPPLIER_BAD_REQUEST;
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

    private static String host(String baseUrl) {
        try {
            return URI.create(baseUrl).getHost();
        } catch (RuntimeException ex) {
            return "unknown";
        }
    }

    private static String orderHash(String orderNo) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            String value = java.util.HexFormat.of().formatHex(digest.digest(orderNo.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return value.substring(0, 12);
        } catch (java.security.NoSuchAlgorithmException ex) {
            return "unknown";
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
