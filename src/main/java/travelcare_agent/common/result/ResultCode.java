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
    DRY_RUN_NOT_READY("DRY_RUN_NOT_READY", "Dry run snapshots are not ready"),
    AGENTOPS_DRY_RUN_REQUIRED("AGENTOPS_DRY_RUN_REQUIRED", "AgentOps debug API requires dryRun=true"),
    MANUAL_REFUND_VERIFICATION_REQUIRED("MANUAL_REFUND_VERIFICATION_REQUIRED", "Manual refund requires verified evidence"),
    HUMAN_REVIEW_STATE_CONFLICT("HUMAN_REVIEW_STATE_CONFLICT", "Human review state conflict"),
    DEEPSEEK_API_KEY_MISSING("DEEPSEEK_API_KEY_MISSING", "DeepSeek API key is missing"),
    EVALUATION_DATASET_NOT_FOUND("EVALUATION_DATASET_NOT_FOUND", "Evaluation dataset not found"),
    EVALUATION_DATASET_NOT_ACTIVE("EVALUATION_DATASET_NOT_ACTIVE", "Evaluation dataset is not active"),
    EVALUATION_DATASET_NOT_DRAFT("EVALUATION_DATASET_NOT_DRAFT", "Evaluation dataset is not draft"),
    EVALUATION_CASE_NOT_FOUND("EVALUATION_CASE_NOT_FOUND", "Evaluation case not found"),
    EVALUATION_CASE_KEY_DUPLICATED("EVALUATION_CASE_KEY_DUPLICATED", "Evaluation case key duplicated"),
    EVALUATION_EMPTY_DATASET("EVALUATION_EMPTY_DATASET", "Evaluation dataset has no enabled cases"),
    EVALUATION_PROVIDER_NOT_ALLOWED("EVALUATION_PROVIDER_NOT_ALLOWED", "Evaluation provider is not allowed"),
    EVALUATION_PROMPT_STUB_UNKNOWN("EVALUATION_PROMPT_STUB_UNKNOWN", "Evaluation prompt stub is unknown"),
    EVALUATION_RUN_NOT_FOUND("EVALUATION_RUN_NOT_FOUND", "Evaluation run not found"),
    EVALUATION_REPORT_NOT_FOUND("EVALUATION_REPORT_NOT_FOUND", "Evaluation report not found"),
    EVALUATION_INVALID_EXPECTATION("EVALUATION_INVALID_EXPECTATION", "Evaluation expectation is invalid"),
    EVALUATION_BASELINE_PROMOTION_NOT_ALLOWED("EVALUATION_BASELINE_PROMOTION_NOT_ALLOWED", "Evaluation baseline promotion is not allowed"),
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
