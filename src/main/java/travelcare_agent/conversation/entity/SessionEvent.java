package travelcare_agent.conversation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import travelcare_agent.enums.SessionEventRole;
import travelcare_agent.enums.SessionEventType;

import java.time.LocalDateTime;

@TableName("session_events")
public class SessionEvent {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long sessionId;
    private Integer seqNo;
    private SessionEventType eventType;
    private SessionEventRole role;
    private String content;
    private String metadataJson;
    private LocalDateTime createdAt;

    public static SessionEvent create(
            Long sessionId,
            Integer seqNo,
            SessionEventType eventType,
            SessionEventRole role,
            String content,
            String metadataJson
    ) {
        SessionEvent event = new SessionEvent();
        event.setSessionId(sessionId);
        event.setSeqNo(seqNo);
        event.setEventType(eventType);
        event.setRole(role);
        event.setContent(content);
        event.setMetadataJson(metadataJson);
        event.setCreatedAt(LocalDateTime.now());
        return event;
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

    public Integer getSeqNo() {
        return seqNo;
    }

    public void setSeqNo(Integer seqNo) {
        this.seqNo = seqNo;
    }

    public SessionEventType getEventType() {
        return eventType;
    }

    public void setEventType(SessionEventType eventType) {
        this.eventType = eventType;
    }

    public SessionEventRole getRole() {
        return role;
    }

    public void setRole(SessionEventRole role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public void setMetadataJson(String metadataJson) {
        this.metadataJson = metadataJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
