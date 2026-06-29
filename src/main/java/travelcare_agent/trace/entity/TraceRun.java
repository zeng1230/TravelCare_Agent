package travelcare_agent.trace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("agent_trace_runs")
public class TraceRun {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String traceId;
    private Long sessionId;
    private Long workflowId;
    private Long userId;
    private Long rootInputEventId;
    private Long rootOutputEventId;
    private String status;
    private String provider;
    private String model;
    private String promptVersion;
    private Boolean dryRun;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;
    private String errorCode;
    private String errorMessage;
    private String metadataJson;

    public Long getId() {
        return id;
    }

    public void setId(Long v) {
        id = v;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String v) {
        traceId = v;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long v) {
        sessionId = v;
    }

    public Long getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(Long v) {
        workflowId = v;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long v) {
        userId = v;
    }

    public Long getRootInputEventId() {
        return rootInputEventId;
    }

    public void setRootInputEventId(Long v) {
        rootInputEventId = v;
    }

    public Long getRootOutputEventId() {
        return rootOutputEventId;
    }

    public void setRootOutputEventId(Long v) {
        rootOutputEventId = v;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String v) {
        status = v;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String v) {
        provider = v;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String v) {
        model = v;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String v) {
        promptVersion = v;
    }

    public Boolean getDryRun() {
        return dryRun;
    }

    public void setDryRun(Boolean v) {
        dryRun = v;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime v) {
        startedAt = v;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime v) {
        finishedAt = v;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long v) {
        durationMs = v;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String v) {
        errorCode = v;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String v) {
        errorMessage = v;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public void setMetadataJson(String v) {
        metadataJson = v;
    }
}
