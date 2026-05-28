package travelcare_agent.common.result;

public enum ResultCode {

    SUCCESS("SUCCESS", "ok"),
    BAD_REQUEST("BAD_REQUEST", "Bad request"),
    UNAUTHORIZED("UNAUTHORIZED", "Unauthorized"),
    FORBIDDEN("FORBIDDEN", "Forbidden"),
    NOT_FOUND("NOT_FOUND", "Not found"),
    ORDER_NOT_FOUND("ORDER_NOT_FOUND", "Order not found"),
    IDEMPOTENCY_KEY_CONFLICT("IDEMPOTENCY_KEY_CONFLICT", "Idempotency key conflict"),
    VALIDATION_FAILED("VALIDATION_FAILED", "Validation failed"),
    WORKFLOW_FAILED("WORKFLOW_FAILED", "Workflow failed"),
    TOOL_CALL_FAILED("TOOL_CALL_FAILED", "Tool call failed"),
    INTERNAL_ERROR("INTERNAL_ERROR", "Internal server error");

    private final String code;
    private final String message;

    ResultCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}
