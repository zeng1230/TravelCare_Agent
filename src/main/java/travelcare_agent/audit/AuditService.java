package travelcare_agent.audit;

import org.springframework.stereotype.Service;
import travelcare_agent.audit.entity.AuditLog;
import travelcare_agent.audit.repository.AuditLogRepository;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    public AuditLog recordOrderQuery(
            Long sessionId,
            Long workflowId,
            Long orderId,
            String afterJson,
            String evidenceJson
    ) {
        return recordSystem(
                sessionId,
                workflowId,
                "ORDER_QUERY",
                "ORDER",
                orderId,
                afterJson,
                evidenceJson
        );
    }

    public AuditLog recordRefundRuleCheck(
            Long sessionId,
            Long workflowId,
            Long refundCaseId,
            String afterJson,
            String evidenceJson
    ) {
        return recordSystem(
                sessionId,
                workflowId,
                "REFUND_RULE_CHECK",
                "REFUND_CASE",
                refundCaseId,
                afterJson,
                evidenceJson
        );
    }

    public AuditLog recordHandoffRequired(Long sessionId, Long workflowId, String reasonCode) {
        return recordSystem(
                sessionId,
                workflowId,
                "HANDOFF_REQUIRED",
                "WORKFLOW",
                workflowId,
                null,
                "{\"reasonCode\":\"" + escape(reasonCode) + "\"}"
        );
    }

    public AuditLog recordTaskDispatch(Long sessionId, Long workflowId, Long taskId) {
        return recordSystem(
                sessionId,
                workflowId,
                "TASK_DISPATCHED",
                "TASK",
                taskId,
                null,
                "{}"
        );
    }

    public AuditLog recordTaskFailure(Long sessionId, Long workflowId, Long taskId, String reason) {
        return recordSystem(
                sessionId,
                workflowId,
                "TASK_FAILED",
                "TASK",
                taskId,
                null,
                "{\"reason\":\"" + escape(reason) + "\"}"
        );
    }

    public List<AuditLog> findBySessionId(Long sessionId) {
        return auditLogRepository.findBySessionId(sessionId);
    }

    public List<AuditLog> findByWorkflowId(Long workflowId) {
        return auditLogRepository.findByWorkflowId(workflowId);
    }

    public List<AuditLog> findByTarget(String targetType, Long targetId) {
        return auditLogRepository.findByTarget(targetType, targetId);
    }

    private AuditLog recordSystem(
            Long sessionId,
            Long workflowId,
            String action,
            String targetType,
            Long targetId,
            String afterJson,
            String evidenceJson
    ) {
        return auditLogRepository.save(AuditLog.system(
                sessionId,
                workflowId,
                action,
                targetType,
                targetId,
                afterJson,
                evidenceJson
        ));
    }

    public AuditLog recordOperator(
            Long sessionId,
            Long workflowId,
            String action,
            String targetType,
            Long targetId,
            String actorType,
            String actorId,
            String afterJson,
            String evidenceJson
    ) {
        AuditLog auditLog = new AuditLog();
        auditLog.setTenantId("default");
        auditLog.setActorType(actorType);
        auditLog.setActorId(actorId);
        auditLog.setSessionId(sessionId);
        auditLog.setWorkflowId(workflowId);
        auditLog.setAction(action);
        auditLog.setTargetType(targetType);
        auditLog.setTargetId(targetId);
        auditLog.setAfterJson(afterJson);
        auditLog.setEvidenceJson(evidenceJson == null ? "{}" : evidenceJson);
        auditLog.setCreatedAt(LocalDateTime.now());
        return auditLogRepository.save(auditLog);
    }

    public AuditLog recordMemoryAction(
            Long sessionId,
            Long workflowId,
            Long memoryId,
            String action,
            String afterJson,
            String evidenceJson
    ) {
        return recordSystem(
                sessionId,
                workflowId,
                "MEMORY_" + action,
                "AGENT_MEMORY",
                memoryId,
                afterJson,
                evidenceJson
        );
    }

    public AuditLog recordKnowledgeRetrieved(Long sessionId, Long workflowId, List<Long> documentIds, List<Long> chunkIds) {
        String evidence = "{\"documentIds\":" + toJsonArray(documentIds) + ",\"chunkIds\":" + toJsonArray(chunkIds) + "}";
        return recordSystem(sessionId, workflowId, "KNOWLEDGE_RETRIEVED", "KNOWLEDGE", null, null, evidence);
    }

    public AuditLog recordMemoryRead(Long sessionId, Long workflowId, List<Long> memoryIds) {
        String evidence = "{\"memoryIds\":" + toJsonArray(memoryIds) + "}";
        return recordSystem(sessionId, workflowId, "MEMORY_READ", "AGENT_MEMORY", null, null, evidence);
    }

    public AuditLog recordContextAssembled(Long sessionId, Long workflowId, List<Long> documentIds, List<Long> chunkIds, List<Long> memoryIds, List<Long> eventIds) {
        String evidence = "{\"documentIds\":" + toJsonArray(documentIds) 
                + ",\"chunkIds\":" + toJsonArray(chunkIds) 
                + ",\"memoryIds\":" + toJsonArray(memoryIds)
                + ",\"eventIds\":" + toJsonArray(eventIds) + "}";
        return recordSystem(sessionId, workflowId, "CONTEXT_ASSEMBLED", "AGENT_CONTEXT", null, null, evidence);
    }

    private static String toJsonArray(List<Long> list) {
        if (list == null || list.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            sb.append(list.get(i));
            if (i < list.size() - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
