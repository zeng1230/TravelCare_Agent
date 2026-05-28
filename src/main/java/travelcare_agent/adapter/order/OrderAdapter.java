package travelcare_agent.adapter.order;

import java.util.Optional;

public interface OrderAdapter {

    Optional<MockOrderAdapter.OrderSnapshot> getOrder(Long orderId, String orderNo, Long userId);
}
