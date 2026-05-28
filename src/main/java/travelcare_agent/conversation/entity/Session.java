package travelcare_agent.conversation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import travelcare_agent.enums.SessionStatus;

import java.time.LocalDateTime;

@TableName("sessions")
public class Session {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String tenantId;
    private Long userId;
    private String channel;
    private SessionStatus status;
    private Long currentWorkflowId;
    private Long contextVersion;
    private LocalDateTime lockedUntil;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static Session create(Long userId, String channel) {
        LocalDateTime now = LocalDateTime.now();
        Session session = new Session();
        session.setTenantId("default");
        session.setUserId(userId);
        session.setChannel(channel);
        session.setStatus(SessionStatus.ACTIVE);
        session.setContextVersion(0L);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        return session;
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

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public SessionStatus getStatus() {
        return status;
    }

    public void setStatus(SessionStatus status) {
        this.status = status;
    }

    public Long getCurrentWorkflowId() {
        return currentWorkflowId;
    }

    public void setCurrentWorkflowId(Long currentWorkflowId) {
        this.currentWorkflowId = currentWorkflowId;
    }

    public Long getContextVersion() {
        return contextVersion;
    }

    public void setContextVersion(Long contextVersion) {
        this.contextVersion = contextVersion;
    }

    public LocalDateTime getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(LocalDateTime lockedUntil) {
        this.lockedUntil = lockedUntil;
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
}
