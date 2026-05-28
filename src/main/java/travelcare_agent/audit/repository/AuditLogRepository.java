package travelcare_agent.audit.repository;

import travelcare_agent.audit.entity.AuditLog;

import java.util.List;

public interface AuditLogRepository {

    AuditLog save(AuditLog auditLog);

    default List<AuditLog> findBySessionId(Long sessionId) {
        throw new UnsupportedOperationException("findBySessionId is not implemented");
    }

    default List<AuditLog> findByWorkflowId(Long workflowId) {
        throw new UnsupportedOperationException("findByWorkflowId is not implemented");
    }

    default List<AuditLog> findByTarget(String targetType, Long targetId) {
        throw new UnsupportedOperationException("findByTarget is not implemented");
    }
}
