package travelcare_agent.audit.repository.mybatis;

import org.springframework.stereotype.Repository;
import travelcare_agent.audit.entity.AuditLog;
import travelcare_agent.audit.repository.AuditLogRepository;

import java.util.List;

@Repository
public class MyBatisAuditLogRepository implements AuditLogRepository {

    private final MyBatisAuditLogMapper mapper;

    public MyBatisAuditLogRepository(MyBatisAuditLogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public AuditLog save(AuditLog auditLog) {
        mapper.insert(auditLog);
        return auditLog;
    }

    @Override
    public List<AuditLog> findBySessionId(Long sessionId) {
        return mapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.<AuditLog>lambdaQuery()
                .eq(AuditLog::getSessionId, sessionId)
                .orderByAsc(AuditLog::getCreatedAt)
                .orderByAsc(AuditLog::getId));
    }

    @Override
    public List<AuditLog> findByWorkflowId(Long workflowId) {
        return mapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.<AuditLog>lambdaQuery()
                .eq(AuditLog::getWorkflowId, workflowId)
                .orderByAsc(AuditLog::getCreatedAt)
                .orderByAsc(AuditLog::getId));
    }

    @Override
    public List<AuditLog> findByTarget(String targetType, Long targetId) {
        return mapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.<AuditLog>lambdaQuery()
                .eq(AuditLog::getTargetType, targetType)
                .eq(AuditLog::getTargetId, targetId)
                .orderByAsc(AuditLog::getCreatedAt)
                .orderByAsc(AuditLog::getId));
    }
}
