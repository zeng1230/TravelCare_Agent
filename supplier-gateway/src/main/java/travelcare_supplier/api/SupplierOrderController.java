package travelcare_supplier.api;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import travelcare_supplier.order.SupplierOrderService;
import travelcare_supplier.order.SupplierOrderSnapshot;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class SupplierOrderController {

    private final SupplierOrderService supplierOrderService;

    public SupplierOrderController(SupplierOrderService supplierOrderService) {
        this.supplierOrderService = supplierOrderService;
    }

    @GetMapping("/supplier/orders/{orderNo}")
    public ResponseEntity<?> getOrder(
            @PathVariable String orderNo,
            @RequestParam Long userId,
            @RequestParam(defaultValue = "success") String scenario
    ) {
        SupplierOrderSnapshot order = supplierOrderService.find(orderNo, userId, scenario);
        if ("malformed".equals(scenario)) {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{");
        }
        if ("missing_field".equals(scenario)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("orderId", order.orderId());
            body.put("orderNo", order.orderNo());
            body.put("userId", order.userId());
            body.put("refundable", order.refundable());
            body.put("paidAmount", order.paidAmount());
            body.put("departureTime", order.departureTime());
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.ok(order);
    }
}
