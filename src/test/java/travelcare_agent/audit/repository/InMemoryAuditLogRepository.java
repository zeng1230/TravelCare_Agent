package travelcare_agent.audit.repository;

import travelcare_agent.audit.entity.AuditLog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class InMemoryAuditLogRepository implements AuditLogRepository {

    private final AtomicLong ids = new AtomicLong(6000);
    private final List<AuditLog> logs = new ArrayList<>();

    @Override
    public synchronized AuditLog save(AuditLog auditLog) {
        if (auditLog.getId() == null) {
            auditLog.setId(ids.incrementAndGet());
        }
        logs.add(auditLog);
        return auditLog;
    }

    public List<AuditLog> findAll() {
        return List.copyOf(logs);
    }

    @Override
    public synchronized List<AuditLog> findBySessionId(Long sessionId) {
        return logs.stream()
                .filter(auditLog -> sessionId != null && sessionId.equals(auditLog.getSessionId()))
                .toList();
    }

    @Override
    public synchronized List<AuditLog> findByWorkflowId(Long workflowId) {
        return logs.stream()
                .filter(auditLog -> workflowId != null && workflowId.equals(auditLog.getWorkflowId()))
                .toList();
    }

    @Override
    public synchronized List<AuditLog> findByTarget(String targetType, Long targetId) {
        return logs.stream()
                .filter(auditLog -> targetType != null && targetType.equals(auditLog.getTargetType()))
                .filter(auditLog -> targetId != null && targetId.equals(auditLog.getTargetId()))
                .toList();
    }
}
