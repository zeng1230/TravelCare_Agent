package travelcare_agent.agentrun.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("agent_runs")
public class AgentRun {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long sessionId;
    private Long workflowId;
    private Long taskId;
    private String correlationId;
    private String runType;
    private String source;
    private String providerMode;
    private String provider;
    private String model;
    private String fallbackProvider;
    private String fallbackModel;
    private String requestHash;
    private String responseHash;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer totalTokens;
    private Boolean fallbackUsed;
    private String inputEventIdsJson;
    private String retrievalChunkIdsJson;
    private String memoryIdsJson;
    private String workflowSnapshotJson;
    private String promptVersion;
    private String responseTemplateVersion;
    private String contextHash;
    private String contextSnapshotHash;
    private String answerHash;
    private Long outputEventId;
    private String status;
    private Long latencyMs;
    private String errorCode;
    private String errorMessage;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String traceId;
    private String spanId;

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

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String getRunType() {
        return runType;
    }

    public void setRunType(String runType) {
        this.runType = runType;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getProviderMode() {
        return providerMode;
    }

    public void setProviderMode(String providerMode) {
        this.providerMode = providerMode;
    }

    public String getFallbackProvider() {
        return fallbackProvider;
    }

    public void setFallbackProvider(String fallbackProvider) {
        this.fallbackProvider = fallbackProvider;
    }

    public String getFallbackModel() {
        return fallbackModel;
    }

    public void setFallbackModel(String fallbackModel) {
        this.fallbackModel = fallbackModel;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public void setRequestHash(String requestHash) {
        this.requestHash = requestHash;
    }

    public String getResponseHash() {
        return responseHash;
    }

    public void setResponseHash(String responseHash) {
        this.responseHash = responseHash;
    }

    public Integer getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(Integer inputTokens) {
        this.inputTokens = inputTokens;
    }

    public Integer getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(Integer outputTokens) {
        this.outputTokens = outputTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Integer totalTokens) {
        this.totalTokens = totalTokens;
    }

    public Boolean getFallbackUsed() {
        return Boolean.TRUE.equals(fallbackUsed);
    }

    public void setFallbackUsed(Boolean fallbackUsed) {
        this.fallbackUsed = fallbackUsed;
    }

    public String getInputEventIdsJson() {
        return inputEventIdsJson;
    }

    public void setInputEventIdsJson(String inputEventIdsJson) {
        this.inputEventIdsJson = inputEventIdsJson;
    }

    public String getRetrievalChunkIdsJson() {
        return retrievalChunkIdsJson;
    }

    public void setRetrievalChunkIdsJson(String retrievalChunkIdsJson) {
        this.retrievalChunkIdsJson = retrievalChunkIdsJson;
    }

    public String getMemoryIdsJson() {
        return memoryIdsJson;
    }

    public void setMemoryIdsJson(String memoryIdsJson) {
        this.memoryIdsJson = memoryIdsJson;
    }

    public String getWorkflowSnapshotJson() {
        return workflowSnapshotJson;
    }

    public void setWorkflowSnapshotJson(String workflowSnapshotJson) {
        this.workflowSnapshotJson = workflowSnapshotJson;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public String getResponseTemplateVersion() {
        return responseTemplateVersion;
    }

    public void setResponseTemplateVersion(String responseTemplateVersion) {
        this.responseTemplateVersion = responseTemplateVersion;
    }

    public String getContextHash() {
        return contextHash;
    }

    public void setContextHash(String contextHash) {
        this.contextHash = contextHash;
    }

    public String getContextSnapshotHash() {
        return contextSnapshotHash;
    }

    public void setContextSnapshotHash(String contextSnapshotHash) {
        this.contextSnapshotHash = contextSnapshotHash;
    }

    public String getAnswerHash() {
        return answerHash;
    }

    public void setAnswerHash(String answerHash) {
        this.answerHash = answerHash;
    }

    public Long getOutputEventId() {
        return outputEventId;
    }

    public void setOutputEventId(Long outputEventId) {
        this.outputEventId = outputEventId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getSpanId() { return spanId; }
    public void setSpanId(String spanId) { this.spanId = spanId; }
}
