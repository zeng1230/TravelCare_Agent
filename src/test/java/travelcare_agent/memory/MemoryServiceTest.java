package travelcare_agent.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import travelcare_agent.audit.AuditService;
import travelcare_agent.audit.entity.AuditLog;
import travelcare_agent.audit.repository.InMemoryAuditLogRepository;
import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.common.result.ResultCode;
import travelcare_agent.enums.MemoryType;
import travelcare_agent.memory.entity.AgentMemory;
import travelcare_agent.memory.repository.InMemoryAgentMemoryRepository;
import travelcare_agent.memory.service.MemoryService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryServiceTest {

    private InMemoryAgentMemoryRepository memoryRepository;
    private InMemoryAuditLogRepository auditRepository;
    private MemoryService memoryService;

    @BeforeEach
    void setUp() {
        memoryRepository = new InMemoryAgentMemoryRepository();
        auditRepository = new InMemoryAuditLogRepository();
        AuditService auditService = new AuditService(auditRepository);
        memoryService = new MemoryService(memoryRepository, auditService);
    }

    @Test
    void testCreateMemoryAndSoftExpiration() {
        Long userId = 100L;
        // 1. Create active memory
        AgentMemory m1 = memoryService.createMemory(
                userId, 1L, 2L,
                MemoryType.USER_PREFERENCE, "seating", "window",
                BigDecimal.valueOf(0.95), null, null
        );
        assertThat(m1).isNotNull();
        assertThat(m1.getId()).isNotNull();

        // 2. Create memory with future expiration
        AgentMemory m2 = memoryService.createMemory(
                userId, 1L, 2L,
                MemoryType.TRIP_CONTEXT, "hotel", "Hilton",
                BigDecimal.valueOf(0.9), null, LocalDateTime.now().plusDays(2)
        );

        // 3. Create expired memory
        AgentMemory m3 = memoryService.createMemory(
                userId, 1L, 2L,
                MemoryType.CASE_SUMMARY, "issue", "delay",
                BigDecimal.valueOf(0.8), null, LocalDateTime.now().minusMinutes(1)
        );

        // Retrieve active memories
        List<AgentMemory> activeList = memoryService.getActiveMemories(userId, null, 10);
        assertThat(activeList).hasSize(2);
        assertThat(activeList).extracting(AgentMemory::getId).containsExactlyInAnyOrder(m1.getId(), m2.getId());
    }

    @Test
    void testNonAuthoritativeBarrier() {
        Long userId = 100L;

        // Try creating with sensitive keys/values
        assertThatThrownBy(() -> memoryService.createMemory(
                userId, 1L, 2L,
                MemoryType.USER_PREFERENCE, "refund_eligibility", "yes",
                BigDecimal.ONE, null, null
        )).isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException bu = (BusinessException) ex;
                    assertThat(bu.getResultCode()).isEqualTo(ResultCode.VALIDATION_FAILED);
                });

        assertThatThrownBy(() -> memoryService.createMemory(
                userId, 1L, 2L,
                MemoryType.USER_PREFERENCE, "seating", "order status is confirmed",
                BigDecimal.ONE, null, null
        )).isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException bu = (BusinessException) ex;
                    assertThat(bu.getResultCode()).isEqualTo(ResultCode.VALIDATION_FAILED);
                });

        // Create standard memory
        AgentMemory memory = memoryService.createMemory(
                userId, 1L, 2L,
                MemoryType.USER_PREFERENCE, "seating", "window",
                BigDecimal.ONE, null, null
        );

        // Update standard memory to have sensitive state
        assertThatThrownBy(() -> memoryService.updateMemoryValue(memory.getId(), "payment state: PAID", BigDecimal.ONE))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException bu = (BusinessException) ex;
                    assertThat(bu.getResultCode()).isEqualTo(ResultCode.VALIDATION_FAILED);
                });
    }

    @Test
    void testAuditRiskNoteAndStatusChange() {
        Long userId = 100L;

        // 1. Create standard USER_PREFERENCE memory - should not trigger write audit log (only RISK_NOTE write is audited)
        AgentMemory m1 = memoryService.createMemory(
                userId, 1L, 2L,
                MemoryType.USER_PREFERENCE, "seating", "aisle",
                BigDecimal.ONE, null, null
        );
        List<AuditLog> logs = auditRepository.findAll();
        assertThat(logs).isEmpty();

        // 2. Create RISK_NOTE memory - must trigger audit log
        AgentMemory m2 = memoryService.createMemory(
                userId, 1L, 2L,
                MemoryType.RISK_NOTE, "safety", "threat",
                BigDecimal.ONE, null, null
        );
        logs = auditRepository.findAll();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getAction()).isEqualTo("MEMORY_CREATE");
        assertThat(logs.get(0).getTargetType()).isEqualTo("AGENT_MEMORY");
        assertThat(logs.get(0).getTargetId()).isEqualTo(m2.getId());

        // 3. Update RISK_NOTE value - must trigger audit log
        memoryService.updateMemoryValue(m2.getId(), "high safety threat", BigDecimal.ONE);
        logs = auditRepository.findAll();
        assertThat(logs).hasSize(2);
        assertThat(logs.get(1).getAction()).isEqualTo("MEMORY_UPDATE");

        // 4. Update status of USER_PREFERENCE memory - must trigger status change audit log
        memoryService.updateStatus(m1.getId(), "INACTIVE");
        logs = auditRepository.findAll();
        assertThat(logs).hasSize(3);
        assertThat(logs.get(2).getAction()).isEqualTo("MEMORY_STATUS_CHANGE");
        assertThat(logs.get(2).getTargetId()).isEqualTo(m1.getId());
    }
}
