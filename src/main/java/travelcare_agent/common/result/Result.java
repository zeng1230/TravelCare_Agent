package travelcare_agent.common.result;

import travelcare_agent.common.trace.TraceIdFilter;

public class Result<T> {

    private final String code;
    private final String message;
    private final T data;
    private final String traceId;

    private Result(String code, String message, T data, String traceId) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.traceId = traceId;
    }

    public static <T> Result<T> success(T data) {
        return of(ResultCode.SUCCESS, data);
    }

    public static <T> Result<T> success() {
        return of(ResultCode.SUCCESS, null);
    }

    public static <T> Result<T> fail(ResultCode resultCode) {
        return of(resultCode, null);
    }

    public static <T> Result<T> fail(ResultCode resultCode, String message) {
        return new Result<>(resultCode.code(), message, null, TraceIdFilter.currentTraceId());
    }

    public static <T> Result<T> of(ResultCode resultCode, T data) {
        return new Result<>(resultCode.code(), resultCode.message(), data, TraceIdFilter.currentTraceId());
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }

    public String getTraceId() {
        return traceId;
    }
}
