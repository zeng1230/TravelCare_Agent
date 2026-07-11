package travelcare_agent.audit;

import org.junit.jupiter.api.Test;
import travelcare_agent.audit.entity.AuditLog;
import travelcare_agent.audit.repository.InMemoryAuditLogRepository;

import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import travelcare_agent.security.CurrentUser;
import travelcare_agent.security.SecurityContextFacade;
import java.util.Set;

class AuditServiceTest {

    @Test
    void authenticatedOperatorIdentityComesFromSecurityContext() {
        InMemoryAuditLogRepository repository = new InMemoryAuditLogRepository();
        AuditService auditService = new AuditService(repository, null, new SecurityContextFacade());
        CurrentUser currentUser = new CurrentUser(4004L, "tenant-a", Set.of("OPERATOR"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(currentUser, null));
        try {
            AuditLog log = auditService.recordAuthenticatedOperator(
                    101L, 201L, "ASSIGN", "HUMAN_REVIEW_CASE", 301L, "{}", "{}");
            assertThat(log.getActorType()).isEqualTo("OPERATOR");
            assertThat(log.getActorId()).isEqualTo("4004");
            assertThat(log.getTenantId()).isEqualTo("tenant-a");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

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
