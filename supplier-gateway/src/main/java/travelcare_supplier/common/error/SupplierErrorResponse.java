package travelcare_supplier.common.error;

public record SupplierErrorResponse(String code, String message, String traceId) {
}
