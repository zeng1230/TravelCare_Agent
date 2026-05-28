package travelcare_agent.audit;

import org.junit.jupiter.api.Test;
import travelcare_agent.audit.entity.AuditLog;
import travelcare_agent.audit.repository.InMemoryAuditLogRepository;

import static org.assertj.core.api.Assertions.assertThat;

class AuditServiceTest {

    @Test
    void recordsAndQueriesAuditLogsForTraceability() {
        InMemoryAuditLogRepository repository = new InMemoryAuditLogRepository();
        AuditService auditService = new AuditService(repository);

        auditService.recordOrderQuery(
                101L,
                201L,
                10L,
                "{\"orderId\":\"10\"}",
                "{\"orderId\":\"10\",\"orderNo\":\"ORD-10\"}"
        );
        auditService.recordHandoffRequired(101L, 201L, "ORDER_NOT_FOUND");

        assertThat(auditService.findBySessionId(101L))
                .extracting(AuditLog::getAction, AuditLog::getWorkflowId, AuditLog::getTargetId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("ORDER_QUERY", 201L, 10L),
                        org.assertj.core.groups.Tuple.tuple("HANDOFF_REQUIRED", 201L, 201L)
                );
        assertThat(auditService.findByWorkflowId(201L)).hasSize(2);
        assertThat(auditService.findByTarget("ORDER", 10L))
                .singleElement()
                .satisfies(auditLog -> assertThat(auditLog.getEvidenceJson()).contains("\"orderNo\":\"ORD-10\""));
    }
}
