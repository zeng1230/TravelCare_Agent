package travelcare_agent.tool.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import travelcare_agent.enums.ToolCallStatus;

import java.time.LocalDateTime;

@TableName("tool_calls")
public class ToolCall {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long sessionId;
    private Long workflowId;
    private Long stepId;
    private String toolName;
    private String idempotencyKey;
    private String requestHash;
    private String requestJson;
    private String responseJson;
    private ToolCallStatus status;
    private Integer retryCount;
    private LocalDateTime timeoutAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Boolean reconciliationRequired;
    private String lastErrorCode;
    private String traceId;
    private String spanId;

    public static ToolCall running(ToolCommandFields fields) {
        ToolCall toolCall = new ToolCall();
        toolCall.setSessionId(fields.sessionId());
        toolCall.setWorkflowId(fields.workflowId());
        toolCall.setStepId(fields.stepId());
        toolCall.setToolName(fields.toolName());
        toolCall.setIdempotencyKey(fields.idempotencyKey());
        toolCall.setRequestHash(fields.requestHash());
        toolCall.setRequestJson(fields.requestJson());
        toolCall.setStatus(ToolCallStatus.RUNNING);
        toolCall.setRetryCount(0);
        toolCall.setTimeoutAt(fields.timeoutAt());
        toolCall.setCreatedAt(LocalDateTime.now());
        return toolCall;
    }

    public void succeed(String responseJson) {
        this.status = ToolCallStatus.SUCCESS;
        this.responseJson = responseJson;
    }

    public void fail(String responseJson) {
        this.status = ToolCallStatus.FAILED;
        this.responseJson = responseJson;
    }

    public void fail(String responseJson, String errorCode) {
        fail(responseJson);
        this.lastErrorCode = errorCode;
        this.updatedAt = LocalDateTime.now();
    }

    public void unknown(String responseJson) {
        this.status = ToolCallStatus.UNKNOWN;
        this.responseJson = responseJson;
        this.reconciliationRequired = true;
        this.updatedAt = LocalDateTime.now();
    }

    public void unknown(String responseJson, String errorCode) {
        unknown(responseJson);
        this.lastErrorCode = errorCode;
    }

    public record ToolCommandFields(
            Long sessionId,
            Long workflowId,
            Long stepId,
            String toolName,
            String idempotencyKey,
            String requestHash,
            String requestJson,
            LocalDateTime timeoutAt
    ) {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public Long getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(Long workflowId) {
        this.workflowId = workflowId;
    }

    public Long getStepId() {
        return stepId;
    }

    public void setStepId(Long stepId) {
        this.stepId = stepId;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public void setRequestHash(String requestHash) {
        this.requestHash = requestHash;
    }

    public String getRequestJson() {
        return requestJson;
    }

    public void setRequestJson(String requestJson) {
        this.requestJson = requestJson;
    }

    public String getResponseJson() {
        return responseJson;
    }

    public void setResponseJson(String responseJson) {
        this.responseJson = responseJson;
    }

    public ToolCallStatus getStatus() {
        return status;
    }

    public void setStatus(ToolCallStatus status) {
        this.status = status;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public LocalDateTime getTimeoutAt() {
        return timeoutAt;
    }

    public void setTimeoutAt(LocalDateTime timeoutAt) {
        this.timeoutAt = timeoutAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Boolean getReconciliationRequired() { return reconciliationRequired; }
    public void setReconciliationRequired(Boolean reconciliationRequired) { this.reconciliationRequired = reconciliationRequired; }
    public String getLastErrorCode() { return lastErrorCode; }
    public void setLastErrorCode(String lastErrorCode) { this.lastErrorCode = lastErrorCode; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getSpanId() { return spanId; }
    public void setSpanId(String spanId) { this.spanId = spanId; }
}
