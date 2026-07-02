package travelcare_agent.adapter.order;

public enum SupplierFailureCode {
    SUPPLIER_ORDER_NOT_FOUND("not_found", false),
    SUPPLIER_BAD_REQUEST("bad_request", true),
    SUPPLIER_TIMEOUT("timeout", true),
    SUPPLIER_UNAVAILABLE("unavailable", true),
    SUPPLIER_INVALID_RESPONSE("invalid_response", true),
    SUPPLIER_MISSING_FIELD("invalid_response", true),
    SUPPLIER_CONNECTION_FAILED("connection_failed", true);

    private final String outcome;
    private final boolean technicalFailure;

    SupplierFailureCode(String outcome, boolean technicalFailure) {
        this.outcome = outcome;
        this.technicalFailure = technicalFailure;
    }

    public String outcome() {
        return outcome;
    }

    public boolean technicalFailure() {
        return technicalFailure;
    }
}
