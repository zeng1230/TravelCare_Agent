package travelcare_agent.adapter.order;

public class SupplierGatewayClientException extends RuntimeException {

    private final SupplierFailureCode failureCode;

    public SupplierGatewayClientException(SupplierFailureCode failureCode, String message) {
        super(failureCode.name() + ": " + message);
        this.failureCode = failureCode;
    }

    public SupplierGatewayClientException(SupplierFailureCode failureCode, String message, Throwable cause) {
        super(failureCode.name() + ": " + message, cause);
        this.failureCode = failureCode;
    }

    public SupplierGatewayClientException(String errorCode, String message) {
        this(parse(errorCode), message);
    }

    public SupplierGatewayClientException(String errorCode, String message, Throwable cause) {
        this(parse(errorCode), message, cause);
    }

    public SupplierFailureCode failureCode() {
        return failureCode;
    }

    public String errorCode() {
        return failureCode.name();
    }

    private static SupplierFailureCode parse(String errorCode) {
        try {
            return SupplierFailureCode.valueOf(errorCode);
        } catch (RuntimeException ex) {
            return SupplierFailureCode.SUPPLIER_UNAVAILABLE;
        }
    }
}
