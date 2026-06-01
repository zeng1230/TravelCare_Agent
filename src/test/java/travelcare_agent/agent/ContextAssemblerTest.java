package travelcare_agent.agent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import travelcare_agent.audit.AuditService;
import travelcare_agent.conversation.entity.Session;
import travelcare_agent.conversation.entity.SessionEvent;
import travelcare_agent.conversation.repository.SessionEventRepository;
import travelcare_agent.conversation.repository.SessionRepository;
import travelcare_agent.enums.MemoryType;
import travelcare_agent.enums.RefundCaseStatus;
import travelcare_agent.enums.SessionEventRole;
import travelcare_agent.enums.SessionEventType;
import travelcare_agent.enums.WorkflowStatus;
import travelcare_agent.memory.entity.AgentMemory;
import travelcare_agent.memory.service.MemoryService;
import travelcare_agent.refund.entity.RefundCase;
import travelcare_agent.refund.repository.RefundCaseRepository;
import travelcare_agent.retrieval.service.KnowledgeIngestionService;
import travelcare_agent.workflow.entity.Workflow;
import travelcare_agent.workflow.repository.WorkflowRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class ContextAssemblerTest {

    @Autowired
    private ContextAssembler contextAssembler;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private SessionEventRepository sessionEventRepository;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private RefundCaseRepository refundCaseRepository;

    @Autowired
    private KnowledgeIngestionService ingestionService;

    @Autowired
    private MemoryService memoryService;

    @Autowired
    private AuditService auditService;

    @Test
    void testAssembleCompleteContext() {
        Long userId = 999L;
        String channel = "WEB";

        // 1. Create a Session
        Session session = Session.create(userId, channel);
        sessionRepository.save(session);

        // 2. Add 25 events (limit is 20, so only last 20 should be returned)
        for (int i = 1; i <= 25; i++) {
            SessionEvent event = SessionEvent.create(
                    session.getId(),
                    i,
                    SessionEventType.MESSAGE,
                    SessionEventRole.USER,
                    "Message " + i,
                    null
            );
            sessionEventRepository.save(event);
        }

        // 3. Create a workflow and refund case, link to session
        Workflow workflow = Workflow.create(session.getId(), "REFUND_INQUIRY");
        workflow.transitionTo(WorkflowStatus.RUNNING, "INIT", "{}");
        workflowRepository.save(workflow);

        RefundCase refundCase = RefundCase.create(
                userId,
                123L,
                workflow.getId(),
                RefundCaseStatus.NEED_HUMAN,
                BigDecimal.valueOf(150.00),
                "flight delay",
                "{}"
        );
        refundCaseRepository.save(refundCase);

        session.setCurrentWorkflowId(workflow.getId());
        sessionRepository.save(session);

        // 4. Ingest policy documents
        ingestionService.ingest(
                "Refund Policy SOP",
                "REFUND_SOP",
                "https://example.com/sop",
                "This policy covers delayed flight refunds.",
                null,
                null
        );

        // 5. Ingest memories (create 15 memories for this user: 8 USER_PREFERENCE, 4 TRIP_CONTEXT, 3 CASE_SUMMARY/expired/inactive)
        // User preferences
        for (int i = 1; i <= 8; i++) {
            memoryService.createMemory(
                    userId, session.getId(), workflow.getId(),
                    MemoryType.USER_PREFERENCE, "pref_" + i, "val_" + i,
                    BigDecimal.valueOf(0.9), null, null
            );
        }
        // Trip contexts
        for (int i = 1; i <= 4; i++) {
            memoryService.createMemory(
                    userId, session.getId(), workflow.getId(),
                    MemoryType.TRIP_CONTEXT, "trip_" + i, "val_" + i,
                    BigDecimal.valueOf(0.9), null, null
            );
        }
        // Other types / expired / inactive
        memoryService.createMemory(
                userId, session.getId(), workflow.getId(),
                MemoryType.CASE_SUMMARY, "case_summary_key", "val",
                BigDecimal.valueOf(0.9), null, null
        ); // Excluded by memory type filter
        AgentMemory expired = memoryService.createMemory(
                userId, session.getId(), workflow.getId(),
                MemoryType.USER_PREFERENCE, "pref_expired", "val",
                BigDecimal.valueOf(0.9), null, LocalDateTime.now().minusSeconds(1)
        ); // Excluded by expiration filter
        AgentMemory inactive = memoryService.createMemory(
                userId, session.getId(), workflow.getId(),
                MemoryType.USER_PREFERENCE, "pref_inactive", "val",
                BigDecimal.valueOf(0.9), null, null
        );
        memoryService.updateStatus(inactive.getId(), "INACTIVE"); // Excluded by status filter

        // 6. Assemble context
        AgentContext context = contextAssembler.assemble(session.getId(), "flight delay");

        // Verify recentEvents is limited to last 20 (seqNo 6 to 25)
        assertThat(context.recentEvents()).hasSize(20);
        assertThat(context.recentEvents().get(0).getSeqNo()).isEqualTo(6);
        assertThat(context.recentEvents().get(19).getSeqNo()).isEqualTo(25);

        // Verify activeWorkflow and refundCase
        assertThat(context.activeWorkflow()).isNotNull();
        assertThat(context.activeWorkflow().getId()).isEqualTo(workflow.getId());
        assertThat(context.refundCase()).isNotNull();
        assertThat(context.refundCase().getId()).isEqualTo(refundCase.getId());

        // Verify policy snippets
        assertThat(context.policySnippets()).isNotEmpty();
        assertThat(context.policySnippets().get(0).content()).contains("delayed flight refunds");

        // Verify active memories (should contain exactly 10, since 8 pref + 4 trip = 12 active memories, capped at 10)
        assertThat(context.activeMemories()).hasSize(10);
        for (AgentMemory mem : context.activeMemories()) {
            assertThat(mem.getStatus()).isEqualTo("ACTIVE");
            assertThat(mem.getExpiresAt() == null || mem.getExpiresAt().isAfter(LocalDateTime.now())).isTrue();
            assertThat(mem.getMemoryType()).isIn(MemoryType.USER_PREFERENCE, MemoryType.TRIP_CONTEXT);
        }
    }

    @Test
    void testAssembleDoesNotWriteAuditLogs() {
        Long userId = 1002L;
        Session session = Session.create(userId, "WEB");
        sessionRepository.save(session);

        sessionEventRepository.save(SessionEvent.create(
                session.getId(),
                1,
                SessionEventType.MESSAGE,
                SessionEventRole.USER,
                "Can I refund order ORD-1001?",
                null
        ));

        ingestionService.ingest(
                "Pure Read Refund SOP",
                "REFUND_SOP",
                "https://example.com/pure-read-sop",
                "Refund policy text for ORD-1001 pure read verification.",
                null,
                null
        );

        contextAssembler.assemble(session.getId(), "Refund policy text");

        assertThat(auditService.findBySessionId(session.getId())).isEmpty();
    }
}
