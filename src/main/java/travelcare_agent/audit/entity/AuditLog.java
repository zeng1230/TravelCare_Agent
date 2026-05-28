package travelcare_agent.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("audit_logs")
public class AuditLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String tenantId;
    private String actorType;
    private String actorId;
    private Long sessionId;
    private Long workflowId;
    private String action;
    private String targetType;
    private Long targetId;
    private String beforeJson;
    private String afterJson;
    private String evidenceJson;
    private LocalDateTime createdAt;

    public static AuditLog system(
            Long sessionId,
            Long workflowId,
            String action,
            String targetType,
            Long targetId,
            String afterJson,
            String evidenceJson
    ) {
        AuditLog auditLog = new AuditLog();
        auditLog.setTenantId("default");
        auditLog.setActorType("SYSTEM");
        auditLog.setActorId("travelcare-agent");
        auditLog.setSessionId(sessionId);
        auditLog.setWorkflowId(workflowId);
        auditLog.setAction(action);
        auditLog.setTargetType(targetType);
        auditLog.setTargetId(targetId);
        auditLog.setAfterJson(afterJson);
        auditLog.setEvidenceJson(evidenceJson == null ? "{}" : evidenceJson);
        auditLog.setCreatedAt(LocalDateTime.now());
        return auditLog;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getActorType() {
        return actorType;
    }

    public void setActorType(String actorType) {
        this.actorType = actorType;
    }

    public String getActorId() {
        return actorId;
    }

    public void setActorId(String actorId) {
        this.actorId = actorId;
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

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public Long getTargetId() {
        return targetId;
    }

    public void setTargetId(Long targetId) {
        this.targetId = targetId;
    }

    public String getBeforeJson() {
        return beforeJson;
    }

    public void setBeforeJson(String beforeJson) {
        this.beforeJson = beforeJson;
    }

    public String getAfterJson() {
        return afterJson;
    }

    public void setAfterJson(String afterJson) {
        this.afterJson = afterJson;
    }

    public String getEvidenceJson() {
        return evidenceJson;
    }

    public void setEvidenceJson(String evidenceJson) {
        this.evidenceJson = evidenceJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
