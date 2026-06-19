package travelcare_agent.common.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import travelcare_agent.common.result.Result;
import travelcare_agent.common.result.ResultCode;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException ex) {
        return ResponseEntity.status(resolveStatus(ex.getResultCode()))
                .body(Result.fail(ex.getResultCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidationException(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse(ResultCode.VALIDATION_FAILED.message());
        return ResponseEntity.badRequest().body(Result.fail(ResultCode.VALIDATION_FAILED, message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception ex) {
        log.error("Unhandled request exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(ResultCode.INTERNAL_ERROR));
    }

    private HttpStatus resolveStatus(ResultCode resultCode) {
        return switch (resultCode) {
            case BAD_REQUEST, VALIDATION_FAILED, IDEMPOTENCY_KEY_CONFLICT, DRY_RUN_NOT_READY,
                    EVALUATION_DATASET_NOT_ACTIVE, EVALUATION_DATASET_NOT_DRAFT, EVALUATION_EMPTY_DATASET,
                    EVALUATION_PROVIDER_NOT_ALLOWED, EVALUATION_PROMPT_STUB_UNKNOWN, EVALUATION_INVALID_EXPECTATION -> HttpStatus.BAD_REQUEST;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND, ORDER_NOT_FOUND, EVALUATION_DATASET_NOT_FOUND, EVALUATION_CASE_NOT_FOUND,
                    EVALUATION_RUN_NOT_FOUND, EVALUATION_REPORT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case EVALUATION_CASE_KEY_DUPLICATED -> HttpStatus.CONFLICT;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
