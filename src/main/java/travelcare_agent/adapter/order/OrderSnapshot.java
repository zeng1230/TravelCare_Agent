package travelcare_agent.adapter.order;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import travelcare_agent.enums.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OrderSnapshot(
        Long orderId,
        String orderNo,
        Long userId,
        OrderStatus status,
        boolean refundable,
        BigDecimal paidAmount,
        LocalDateTime departureTime
) {
    @JsonCreator
    public OrderSnapshot(
            @JsonProperty("orderId") Long orderId,
            @JsonProperty("orderNo") String orderNo,
            @JsonProperty("userId") Long userId,
            @JsonProperty("status") OrderStatus status,
            @JsonProperty("refundable") boolean refundable,
            @JsonProperty("paidAmount") BigDecimal paidAmount,
            @JsonProperty("departureTime") LocalDateTime departureTime
    ) {
        this.orderId = orderId;
        this.orderNo = orderNo;
        this.userId = userId;
        this.status = status;
        this.refundable = refundable;
        this.paidAmount = paidAmount;
        this.departureTime = departureTime;
    }
}
