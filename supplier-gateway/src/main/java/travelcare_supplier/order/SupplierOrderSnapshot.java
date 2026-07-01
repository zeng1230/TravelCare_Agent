package travelcare_supplier.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record SupplierOrderSnapshot(
        Long orderId,
        String orderNo,
        Long userId,
        SupplierOrderStatus status,
        boolean refundable,
        BigDecimal paidAmount,
        LocalDateTime departureTime
) {
}
