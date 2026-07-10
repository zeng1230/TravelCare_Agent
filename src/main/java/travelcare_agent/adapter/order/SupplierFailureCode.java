package travelcare_agent.adapter.order;

public enum SupplierFailureCode {
    SUPPLIER_ORDER_NOT_FOUND("not_found", false, false, false),
    SUPPLIER_BAD_REQUEST("bad_request", false, false, true),
    SUPPLIER_TIMEOUT("timeout", true, true, true),
    SUPPLIER_UNAVAILABLE("unavailable", true, true, true),
    SUPPLIER_INVALID_RESPONSE("invalid_response", false, true, true),
    SUPPLIER_MISSING_FIELD("invalid_response", false, true, true),
    SUPPLIER_CONNECTION_FAILED("connection_failed", true, true, true),
    SUPPLIER_CIRCUIT_OPEN("circuit_open", false, false, true);

    private final String outcome;
    private final boolean retryable;
    private final boolean circuitBreakerFailure;
    private final boolean technicalFailure;

    SupplierFailureCode(String outcome, boolean retryable, boolean circuitBreakerFailure, boolean technicalFailure) {
        this.outcome = outcome;
        this.retryable = retryable;
        this.circuitBreakerFailure = circuitBreakerFailure;
        this.technicalFailure = technicalFailure;
    }

    public String outcome() {
        return outcome;
    }

    public boolean technicalFailure() {
        return technicalFailure;
    }

    public boolean retryable() {
        return retryable;
    }

    public boolean circuitBreakerFailure() {
        return circuitBreakerFailure;
    }
}
