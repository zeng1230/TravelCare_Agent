package travelcare_agent.adapter.order;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import travelcare_agent.enums.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

@Component
@ConditionalOnProperty(name = "travelcare.supplier.mode", havingValue = "mock", matchIfMissing = true)
public class MockOrderAdapter implements OrderAdapter {

    private final OrderLookup lookup;

    public MockOrderAdapter() {
        this((orderId, orderNo, userId) -> {
            if ("ORD-1001".equalsIgnoreCase(orderNo)) {
                return Optional.of(new OrderSnapshot(1001L, "ORD-1001", userId, OrderStatus.PAID, true, new BigDecimal("100.00"), LocalDateTime.now().plusDays(5)));
            }
            if ("ORD-1002".equalsIgnoreCase(orderNo)) {
                return Optional.of(new OrderSnapshot(1002L, "ORD-1002", userId, OrderStatus.PAID, false, new BigDecimal("50.00"), LocalDateTime.now().plusDays(5)));
            }
            return Optional.empty();
        });
    }

    public MockOrderAdapter(OrderLookup lookup) {
        this.lookup = lookup;
    }

    @Override
    public Optional<OrderSnapshot> getOrder(Long orderId, String orderNo, Long userId) {
        travelcare_agent.dryrun.SideEffectGuard.checkCurrent(travelcare_agent.dryrun.SideEffectOperation.EXTERNAL_ADAPTER_CALL);
        return lookup.find(orderId, orderNo, userId);
    }

    @FunctionalInterface
    public interface OrderLookup {
        Optional<OrderSnapshot> find(Long orderId, String orderNo, Long userId);
    }
}
