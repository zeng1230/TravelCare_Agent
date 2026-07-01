package travelcare_supplier.order;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Service
public class SupplierOrderService {

    private final Map<String, SupplierOrderSnapshot> orders = Map.of(
            "ORD-1001", new SupplierOrderSnapshot(
                    1001L, "ORD-1001", 1001L, SupplierOrderStatus.PAID, true,
                    new BigDecimal("100.00"), LocalDateTime.parse("2026-05-13T10:00:00")),
            "ORD-1002", new SupplierOrderSnapshot(
                    1002L, "ORD-1002", 1001L, SupplierOrderStatus.PAID, false,
                    new BigDecimal("50.00"), LocalDateTime.parse("2026-05-13T10:00:00"))
    );

    public SupplierOrderSnapshot find(String orderNo, Long userId, String scenario) {
        if (orderNo == null || orderNo.isBlank() || userId == null) {
            throw new IllegalArgumentException("orderNo and userId are required");
        }
        if ("not_found".equals(scenario)) {
            throw new SupplierOrderNotFoundException();
        }
        if ("timeout".equals(scenario)) {
            throw new SupplierScenarioException(HttpStatus.GATEWAY_TIMEOUT, "SUPPLIER_TIMEOUT", "Supplier timeout");
        }
        if ("server_error".equals(scenario)) {
            throw new SupplierScenarioException(HttpStatus.INTERNAL_SERVER_ERROR, "SUPPLIER_INTERNAL_ERROR", "Supplier internal error");
        }
        SupplierOrderSnapshot order = orders.get(orderNo.toUpperCase(java.util.Locale.ROOT));
        if (order == null) {
            throw new SupplierOrderNotFoundException();
        }
        return order;
    }
}
