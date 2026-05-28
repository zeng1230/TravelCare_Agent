package travelcare_agent.memory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import travelcare_agent.audit.entity.AuditLog;
import travelcare_agent.audit.repository.mybatis.MyBatisAuditLogMapper;
import travelcare_agent.enums.MemoryType;
import travelcare_agent.memory.entity.AgentMemory;
import travelcare_agent.memory.repository.mybatis.MyBatisAgentMemoryMapper;
import travelcare_agent.memory.service.MemoryService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class MemoryServiceIntegrationTest {

    @Autowired
    private MemoryService memoryService;

    @Autowired
    private MyBatisAgentMemoryMapper memoryMapper;

    @Autowired
    private MyBatisAuditLogMapper auditLogMapper;

    @Test
    void testDatabaseOperationsAndSoftExpiration() {
        Long userId = 1001L;

        // 1. Ingest memories into the actual database
        AgentMemory activeMemory = memoryService.createMemory(
                userId, 10L, 20L,
                MemoryType.USER_PREFERENCE, "preferred_airline", "Delta",
                BigDecimal.valueOf(0.99), 11L, null
        );

        AgentMemory expiredMemory = memoryService.createMemory(
                userId, 10L, 20L,
                MemoryType.TRIP_CONTEXT, "trip_city", "Chicago",
                BigDecimal.valueOf(0.85), 12L, LocalDateTime.now().minusSeconds(10)
        );

        AgentMemory inactiveMemory = memoryService.createMemory(
                userId, 10L, 20L,
                MemoryType.TRIP_CONTEXT, "trip_hotel", "Marriott",
                BigDecimal.valueOf(0.9), 13L, null
        );
        memoryService.updateStatus(inactiveMemory.getId(), "INACTIVE");

        // Verify that they are persisted in database
        assertThat(memoryMapper.selectById(activeMemory.getId())).isNotNull();
        assertThat(memoryMapper.selectById(expiredMemory.getId())).isNotNull();
        assertThat(memoryMapper.selectById(inactiveMemory.getId())).isNotNull();

        // 2. Query active memories for the user
        List<AgentMemory> activeMemories = memoryService.getActiveMemories(userId, null, 10);

        // Only activeMemory should be retrieved because:
        // - expiredMemory is expired
        // - inactiveMemory is INACTIVE
        assertThat(activeMemories).hasSize(1);
        assertThat(activeMemories.get(0).getId()).isEqualTo(activeMemory.getId());
        assertThat(activeMemories.get(0).getMemoryValue()).isEqualTo("Delta");
    }

    @Test
    void testAuditLogInsertionForRiskNote() {
        Long userId = 1002L;

        // Create a RISK_NOTE memory item
        AgentMemory riskMemory = memoryService.createMemory(
                userId, 15L, 25L,
                MemoryType.RISK_NOTE, "medical_safety", "allergic to nuts",
                BigDecimal.ONE, 101L, null
        );

        // Verify the RISK_NOTE creation is audited in audit_logs table
        List<AuditLog> auditLogs = auditLogMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<AuditLog>lambdaQuery()
                        .eq(AuditLog::getTargetType, "AGENT_MEMORY")
                        .eq(AuditLog::getTargetId, riskMemory.getId())
        );

        assertThat(auditLogs).hasSize(1);
        assertThat(auditLogs.get(0).getAction()).isEqualTo("MEMORY_CREATE");
        assertThat(auditLogs.get(0).getTenantId()).isEqualTo("default");
    }
}
