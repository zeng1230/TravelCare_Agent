package travelcare_agent.tool;

import org.junit.jupiter.api.Test;
import travelcare_agent.adapter.order.MockOrderAdapter;
import travelcare_agent.adapter.order.OrderSnapshot;
import travelcare_agent.adapter.order.SupplierFailureCode;
import travelcare_agent.adapter.order.SupplierGatewayClientException;
import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.common.result.ResultCode;
import travelcare_agent.enums.OrderStatus;
import travelcare_agent.enums.ToolCallStatus;
import travelcare_agent.tool.entity.ToolCall;
import travelcare_agent.tool.entity.IdempotencyKey;
import travelcare_agent.tool.repository.IdempotencyKeyRepository;
import travelcare_agent.tool.repository.ToolCallRepository;
import travelcare_agent.tool.tools.GetOrderTool;
import travelcare_agent.reconciliation.ReconciliationService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ToolServiceTest {

    @Test
    void recordsRunningThenSuccessAndReturnsOrderSnapshot() {
        Fixture fixture = Fixture.withOrder(order());

        GetOrderTool.GetOrderResult result = fixture.getOrderTool.execute(request("tool:get_order:20:10"));

        assertThat(result.order().orderId()).isEqualTo(10L);
        assertThat(fixture.toolCallRepository.count()).isEqualTo(1);
        ToolCall toolCall = fixture.toolCallRepository.only();
        assertThat(toolCall.getStatus()).isEqualTo(ToolCallStatus.SUCCESS);
        assertThat(toolCall.getToolName()).isEqualTo("GetOrderTool");
        assertThat(toolCall.getRequestJson()).contains("\"orderId\":10");
        assertThat(toolCall.getResponseJson()).contains("\"orderNo\":\"ORD-10\"");
    }

    @Test
    void reusesFirstResultForDuplicateIdempotencyKeyAndRequestHash() {
        Fixture fixture = Fixture.withOrder(order());
        GetOrderTool.GetOrderRequest request = request("tool:get_order:20:10");

        GetOrderTool.GetOrderResult first = fixture.getOrderTool.execute(request);
        GetOrderTool.GetOrderResult duplicate = fixture.getOrderTool.execute(request);

        assertThat(duplicate.order().orderNo()).isEqualTo(first.order().orderNo());
        assertThat(fixture.toolCallRepository.count()).isEqualTo(1);
        assertThat(fixture.adapterCalls).hasValue(1);
    }

    @Test
    void rejectsSameIdempotencyKeyWithDifferentRequestHash() {
        Fixture fixture = Fixture.withOrder(order());
        fixture.getOrderTool.execute(request("same-key", 10L, null));

        assertThatThrownBy(() -> fixture.getOrderTool.execute(request("same-key", 11L, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("resultCode")
                .isEqualTo(ResultCode.IDEMPOTENCY_KEY_CONFLICT);
        assertThat(fixture.toolCallRepository.count()).isEqualTo(1);
    }

    @Test
    void recordsFailedWhenOrderIsMissing() {
        Fixture fixture = Fixture.withOrder(null);

        assertThatThrownBy(() -> fixture.getOrderTool.execute(request("tool:get_order:20:404", 404L, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("resultCode")
                .isEqualTo(ResultCode.ORDER_NOT_FOUND);

        ToolCall toolCall = fixture.toolCallRepository.only();
        assertThat(toolCall.getStatus()).isEqualTo(ToolCallStatus.FAILED);
        assertThat(toolCall.getResponseJson()).contains("ORDER_NOT_FOUND");
    }

    @Test
    void rejectsOrderOwnedByAnotherUserWithoutSuccessfulToolResult() {
        Fixture fixture = Fixture.withOrder(new OrderSnapshot(
                10L,
                "ORD-10",
                2002L,
                OrderStatus.PAID,
                true,
                new BigDecimal("399.00"),
                LocalDateTime.parse("2026-05-10T10:00:00")
        ));

        assertThatThrownBy(() -> fixture.getOrderTool.execute(request("tool:get_order:20:foreign", 10L, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("resultCode")
                .isEqualTo(ResultCode.ORDER_NOT_FOUND);

        ToolCall toolCall = fixture.toolCallRepository.only();
        assertThat(toolCall.getStatus()).isEqualTo(ToolCallStatus.FAILED);
        assertThat(toolCall.getResponseJson()).contains("ORDER_NOT_FOUND");
        assertThat(toolCall.getResponseJson()).doesNotContain("SUCCESS");
    }

    @Test
    void recordsFailedTimeoutForReadOnlyAdapterException() {
        Fixture fixture = Fixture.withFailingAdapter();

        assertThatThrownBy(() -> fixture.getOrderTool.execute(request("tool:get_order:20:10")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("adapter timeout");

        ToolCall toolCall = fixture.toolCallRepository.only();
        assertThat(toolCall.getStatus()).isEqualTo(ToolCallStatus.FAILED);
        assertThat(toolCall.getResponseJson()).contains("TOOL_TIMEOUT");
        assertThat(toolCall.getReconciliationRequired()).isFalse();
    }

    @Test
    void recordsSupplierFailureErrorCodeForReadOnlyAdapterException() {
        Fixture fixture = Fixture.withSupplierFailure(SupplierFailureCode.SUPPLIER_UNAVAILABLE);

        assertThatThrownBy(() -> fixture.getOrderTool.execute(request("tool:get_order:20:server-error")))
                .isInstanceOf(SupplierGatewayClientException.class)
                .hasMessageContaining("SUPPLIER_UNAVAILABLE");

        ToolCall toolCall = fixture.toolCallRepository.only();
        assertThat(toolCall.getStatus()).isEqualTo(ToolCallStatus.FAILED);
        assertThat(toolCall.getLastErrorCode()).isEqualTo("SUPPLIER_UNAVAILABLE");
        assertThat(toolCall.getResponseJson()).contains("SUPPLIER_UNAVAILABLE");
        assertThat(toolCall.getReconciliationRequired()).isFalse();
    }

    @Test
    void recordsMalformedSupplierResponseErrorCodeForReadOnlyAdapterException() {
        Fixture fixture = Fixture.withSupplierFailure(SupplierFailureCode.SUPPLIER_INVALID_RESPONSE);

        assertThatThrownBy(() -> fixture.getOrderTool.execute(request("tool:get_order:20:malformed")))
                .isInstanceOf(SupplierGatewayClientException.class)
                .hasMessageContaining("SUPPLIER_INVALID_RESPONSE");

        ToolCall toolCall = fixture.toolCallRepository.only();
        assertThat(toolCall.getStatus()).isEqualTo(ToolCallStatus.FAILED);
        assertThat(toolCall.getLastErrorCode()).isEqualTo("SUPPLIER_INVALID_RESPONSE");
        assertThat(toolCall.getResponseJson()).contains("SUPPLIER_INVALID_RESPONSE");
    }

    @Test
    void recordsMissingFieldSupplierResponseErrorCodeForReadOnlyAdapterException() {
        Fixture fixture = Fixture.withSupplierFailure(SupplierFailureCode.SUPPLIER_MISSING_FIELD);

        assertThatThrownBy(() -> fixture.getOrderTool.execute(request("tool:get_order:20:missing-field")))
                .isInstanceOf(SupplierGatewayClientException.class)
                .hasMessageContaining("SUPPLIER_MISSING_FIELD");

        ToolCall toolCall = fixture.toolCallRepository.only();
        assertThat(toolCall.getStatus()).isEqualTo(ToolCallStatus.FAILED);
        assertThat(toolCall.getLastErrorCode()).isEqualTo("SUPPLIER_MISSING_FIELD");
        assertThat(toolCall.getResponseJson()).contains("SUPPLIER_MISSING_FIELD");
    }

    @Test
    void recordsPreciseSupplierTimeoutAndConnectionFailureCodesForReadOnlyAdapterException() {
        Fixture timeout = Fixture.withSupplierFailure(SupplierFailureCode.SUPPLIER_TIMEOUT);

        assertThatThrownBy(() -> timeout.getOrderTool.execute(request("tool:get_order:20:timeout")))
                .isInstanceOf(SupplierGatewayClientException.class);

        ToolCall timeoutCall = timeout.toolCallRepository.only();
        assertThat(timeoutCall.getStatus()).isEqualTo(ToolCallStatus.FAILED);
        assertThat(timeoutCall.getLastErrorCode()).isEqualTo("SUPPLIER_TIMEOUT");
        assertThat(timeoutCall.getReconciliationRequired()).isFalse();

        Fixture connection = Fixture.withSupplierFailure(SupplierFailureCode.SUPPLIER_CONNECTION_FAILED);

        assertThatThrownBy(() -> connection.getOrderTool.execute(request("tool:get_order:20:connection")))
                .isInstanceOf(SupplierGatewayClientException.class);

        ToolCall connectionCall = connection.toolCallRepository.only();
        assertThat(connectionCall.getStatus()).isEqualTo(ToolCallStatus.FAILED);
        assertThat(connectionCall.getLastErrorCode()).isEqualTo("SUPPLIER_CONNECTION_FAILED");
        assertThat(connectionCall.getReconciliationRequired()).isFalse();
    }

    @Test
    void recordsUnknownAndCreatesReconciliationForSideEffectingTimeout() {
        InMemoryToolCallRepository toolCallRepository = new InMemoryToolCallRepository();
        IdempotencyKeyRepository idempotencyKeyRepository = new InMemoryIdempotencyKeyRepository();
        IdempotencyService idempotencyService = new IdempotencyService(idempotencyKeyRepository);
        ReconciliationService reconciliationService = mock(ReconciliationService.class);
        ToolService service = new ToolService(
                toolCallRepository,
                idempotencyService,
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(),
                null,
                reconciliationService);

        ToolService.ToolCommand command = new ToolService.ToolCommand(
                101L, 20L, 30L, "RefundTool", "tool:refund:1",
                "hash-1", "{\"refundId\":1}", LocalDateTime.now().plusSeconds(1), true);

        assertThatThrownBy(() -> service.execute(command, String.class, () -> {
            throw new java.util.concurrent.CompletionException(
                    new java.util.concurrent.TimeoutException("supplier timeout"));
        })).isInstanceOf(java.util.concurrent.CompletionException.class);

        ToolCall toolCall = toolCallRepository.only();
        assertThat(toolCall.getStatus()).isEqualTo(ToolCallStatus.UNKNOWN);
        assertThat(toolCall.getReconciliationRequired()).isTrue();
        verify(reconciliationService).createOrReusePending(
                eq("tool_call"), eq(toolCall.getId()), eq("TOOL_TIMEOUT"), any());
    }

    private static GetOrderTool.GetOrderRequest request(String idempotencyKey) {
        return request(idempotencyKey, 10L, null);
    }

    private static GetOrderTool.GetOrderRequest request(String idempotencyKey, Long orderId, String orderNo) {
        return new GetOrderTool.GetOrderRequest(101L, 20L, 30L, 1001L, orderId, orderNo, idempotencyKey);
    }

    private static OrderSnapshot order() {
        return new OrderSnapshot(
                10L,
                "ORD-10",
                1001L,
                OrderStatus.PAID,
                true,
                new BigDecimal("399.00"),
                LocalDateTime.parse("2026-05-10T10:00:00")
        );
    }

    private static final class Fixture {
        private final InMemoryToolCallRepository toolCallRepository;
        private final AtomicInteger adapterCalls;
        private final GetOrderTool getOrderTool;

        private Fixture(
                InMemoryToolCallRepository toolCallRepository,
                AtomicInteger adapterCalls,
                GetOrderTool getOrderTool
        ) {
            this.toolCallRepository = toolCallRepository;
            this.adapterCalls = adapterCalls;
            this.getOrderTool = getOrderTool;
        }

        static Fixture withOrder(OrderSnapshot order) {
            AtomicInteger calls = new AtomicInteger();
            return create((orderId, orderNo, userId) -> {
                calls.incrementAndGet();
                return Optional.ofNullable(order)
                        .filter(snapshot -> orderId == null || snapshot.orderId().equals(orderId))
                        .filter(snapshot -> orderNo == null || snapshot.orderNo().equals(orderNo))
                        .filter(snapshot -> snapshot.userId().equals(userId));
            }, calls);
        }

        static Fixture withFailingAdapter() {
            AtomicInteger calls = new AtomicInteger();
            return create((orderId, orderNo, userId) -> {
                calls.incrementAndGet();
                throw new IllegalStateException("adapter timeout");
            }, calls);
        }

        static Fixture withSupplierFailure(SupplierFailureCode failureCode) {
            AtomicInteger calls = new AtomicInteger();
            return create((orderId, orderNo, userId) -> {
                calls.incrementAndGet();
                throw new SupplierGatewayClientException(failureCode, "supplier failure");
            }, calls);
        }

        private static Fixture create(MockOrderAdapter.OrderLookup lookup, AtomicInteger calls) {
            InMemoryToolCallRepository toolCallRepository = new InMemoryToolCallRepository();
            IdempotencyKeyRepository idempotencyKeyRepository = new InMemoryIdempotencyKeyRepository();
            IdempotencyService idempotencyService = new IdempotencyService(idempotencyKeyRepository);
            ToolService toolService = new ToolService(toolCallRepository, idempotencyService);
            GetOrderTool getOrderTool = new GetOrderTool(toolService, new MockOrderAdapter(lookup));
            return new Fixture(toolCallRepository, calls, getOrderTool);
        }
    }

    private static final class InMemoryToolCallRepository implements ToolCallRepository {
        private final AtomicLong ids = new AtomicLong(5000);
        private final Map<Long, ToolCall> calls = new ConcurrentHashMap<>();

        @Override
        public ToolCall save(ToolCall toolCall) {
            if (toolCall.getId() == null) {
                toolCall.setId(ids.incrementAndGet());
            }
            if (toolCall.getReconciliationRequired() == null) {
                toolCall.setReconciliationRequired(false);
            }
            calls.put(toolCall.getId(), toolCall);
            return toolCall;
        }

        @Override
        public Optional<ToolCall> findById(Long id) {
            return Optional.ofNullable(calls.get(id));
        }

        @Override
        public Optional<ToolCall> findByIdempotencyKey(String idempotencyKey) {
            return calls.values().stream()
                    .filter(call -> call.getIdempotencyKey().equals(idempotencyKey))
                    .findFirst();
        }

        int count() {
            return calls.size();
        }

        ToolCall only() {
            assertThat(calls).hasSize(1);
            return calls.values().iterator().next();
        }
    }

    private static final class InMemoryIdempotencyKeyRepository implements IdempotencyKeyRepository {
        private final Map<String, IdempotencyKey> keys = new ConcurrentHashMap<>();

        @Override
        public IdempotencyKey save(IdempotencyKey idempotencyKey) {
            keys.put(idempotencyKey.getIdempotencyKey(), idempotencyKey);
            return idempotencyKey;
        }

        @Override
        public Optional<IdempotencyKey> findByKey(String idempotencyKey) {
            return Optional.ofNullable(keys.get(idempotencyKey));
        }
    }
}
