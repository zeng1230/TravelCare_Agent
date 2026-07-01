package travelcare_agent.adapter.order;

public class SupplierGatewayClientException extends RuntimeException {

    private final String errorCode;

    public SupplierGatewayClientException(String errorCode, String message) {
        super(errorCode + ": " + message);
        this.errorCode = errorCode;
    }

    public SupplierGatewayClientException(String errorCode, String message, Throwable cause) {
        super(errorCode + ": " + message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
