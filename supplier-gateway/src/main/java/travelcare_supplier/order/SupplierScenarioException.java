package travelcare_supplier.order;

import org.springframework.http.HttpStatus;

public class SupplierScenarioException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public SupplierScenarioException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
