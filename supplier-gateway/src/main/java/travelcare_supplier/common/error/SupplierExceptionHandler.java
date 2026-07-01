package travelcare_supplier.common.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import travelcare_supplier.common.trace.TraceIdFilter;
import travelcare_supplier.order.SupplierOrderNotFoundException;
import travelcare_supplier.order.SupplierScenarioException;

@RestControllerAdvice
public class SupplierExceptionHandler {

    @ExceptionHandler(SupplierOrderNotFoundException.class)
    public ResponseEntity<SupplierErrorResponse> handleNotFound(SupplierOrderNotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order not found");
    }

    @ExceptionHandler(SupplierScenarioException.class)
    public ResponseEntity<SupplierErrorResponse> handleScenario(SupplierScenarioException ex) {
        return error(ex.status(), ex.code(), ex.getMessage());
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, IllegalArgumentException.class})
    public ResponseEntity<SupplierErrorResponse> handleBadRequest(Exception ex) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<SupplierErrorResponse> handleUnknown(Exception ex) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "SUPPLIER_INTERNAL_ERROR", "Supplier internal error");
    }

    private ResponseEntity<SupplierErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .body(new SupplierErrorResponse(code, message, TraceIdFilter.currentTraceId()));
    }
}
