package travelcare_agent.tool;

import org.junit.jupiter.api.Test;
import travelcare_agent.adapter.order.MockOrderAdapter;
import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.common.result.ResultCode;
import travelcare_agent.enums.OrderStatus;
import travelcare_agent.enums.ToolCallStatus;
import travelcare_agent.tool.entity.ToolCall;
import travelcare_agent.tool.entity.IdempotencyKey;
import travelcare_agent.tool.repository.IdempotencyKeyRepository;
import travelcare_agent.tool.repository.ToolCallRepository;
import travelcare_agent.tool.tools.GetOrderTool;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void recordsUnknownWhenAdapterThrowsUnexpectedException() {
        Fixture fixture = Fixture.withFailingAdapter();

        assertThatThrownBy(() -> fixture.getOrderTool.execute(request("tool:get_order:20:10")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("adapter timeout");

        ToolCall toolCall = fixture.toolCallRepository.only();
        assertThat(toolCall.getStatus()).isEqualTo(ToolCallStatus.UNKNOWN);
        assertThat(toolCall.getResponseJson()).contains("UNKNOWN_TOOL_ERROR");
    }

    private static GetOrderTool.GetOrderRequest request(String idempotencyKey) {
        return request(idempotencyKey, 10L, null);
    }

    private static GetOrderTool.GetOrderRequest request(String idempotencyKey, Long orderId, String orderNo) {
        return new GetOrderTool.GetOrderRequest(101L, 20L, 30L, 1001L, orderId, orderNo, idempotencyKey);
    }

    private static MockOrderAdapter.OrderSnapshot order() {
        return new MockOrderAdapter.OrderSnapshot(
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

        static Fixture withOrder(MockOrderAdapter.OrderSnapshot order) {
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
