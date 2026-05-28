package travelcare_agent.memory.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import travelcare_agent.audit.AuditService;
import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.common.result.ResultCode;
import travelcare_agent.enums.MemoryType;
import travelcare_agent.memory.entity.AgentMemory;
import travelcare_agent.memory.repository.AgentMemoryRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class MemoryService {

    private final AgentMemoryRepository repository;
    private final AuditService auditService;

    public MemoryService(AgentMemoryRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    @Transactional
    public AgentMemory createMemory(
            Long userId,
            Long sessionId,
            Long workflowId,
            MemoryType memoryType,
            String memoryKey,
            String memoryValue,
            BigDecimal confidence,
            Long sourceEventId,
            LocalDateTime expiresAt
    ) {
        validateNonAuthoritativeBarrier(memoryKey, memoryValue);

        LocalDateTime now = LocalDateTime.now();
        AgentMemory memory = new AgentMemory();
        memory.setUserId(userId);
        memory.setSessionId(sessionId);
        memory.setWorkflowId(workflowId);
        memory.setMemoryType(memoryType);
        memory.setMemoryKey(memoryKey);
        memory.setMemoryValue(memoryValue);
        memory.setConfidence(confidence == null ? BigDecimal.ONE : confidence);
        memory.setSourceEventId(sourceEventId);
        memory.setStatus("ACTIVE");
        memory.setExpiresAt(expiresAt);
        memory.setCreatedAt(now);
        memory.setUpdatedAt(now);

        memory = repository.save(memory);

        if (memoryType == MemoryType.RISK_NOTE) {
            auditService.recordMemoryAction(
                    sessionId,
                    workflowId,
                    memory.getId(),
                    "CREATE",
                    "{\"status\":\"ACTIVE\",\"memoryType\":\"RISK_NOTE\"}",
                    "{\"key\":\"" + escape(memoryKey) + "\"}"
            );
        }

        return memory;
    }

    @Transactional
    public AgentMemory updateMemoryValue(Long id, String newValue, BigDecimal confidence) {
        AgentMemory memory = repository.findById(id)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Memory item not found: " + id));

        validateNonAuthoritativeBarrier(memory.getMemoryKey(), newValue);

        String oldValue = memory.getMemoryValue();
        memory.setMemoryValue(newValue);
        if (confidence != null) {
            memory.setConfidence(confidence);
        }
        memory.setUpdatedAt(LocalDateTime.now());
        memory = repository.save(memory);

        if (memory.getMemoryType() == MemoryType.RISK_NOTE) {
            auditService.recordMemoryAction(
                    memory.getSessionId(),
                    memory.getWorkflowId(),
                    memory.getId(),
                    "UPDATE",
                    "{\"newValue\":\"" + escape(newValue) + "\"}",
                    "{\"oldValue\":\"" + escape(oldValue) + "\"}"
            );
        }

        return memory;
    }

    @Transactional
    public AgentMemory updateStatus(Long id, String status) {
        AgentMemory memory = repository.findById(id)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Memory item not found: " + id));

        String oldStatus = memory.getStatus();
        if (oldStatus.equals(status)) {
            return memory;
        }

        memory.setStatus(status);
        memory.setUpdatedAt(LocalDateTime.now());
        memory = repository.save(memory);

        auditService.recordMemoryAction(
                memory.getSessionId(),
                memory.getWorkflowId(),
                memory.getId(),
                "STATUS_CHANGE",
                "{\"status\":\"" + status + "\"}",
                "{\"oldStatus\":\"" + oldStatus + "\"}"
        );

        return memory;
    }

    public List<AgentMemory> getActiveMemories(Long userId, List<MemoryType> types, int limit) {
        return repository.findActiveMemories(userId, types, limit);
    }

    private void validateNonAuthoritativeBarrier(String key, String value) {
        if (key == null || value == null) {
            return;
        }
        String kLower = key.toLowerCase();
        String vLower = value.toLowerCase();

        List<String> forbiddenSubstrings = List.of(
                "refund_eligibility", "refund eligibility", "refundeligibility",
                "refund_eligible", "refund eligible", "refundeligible",
                "order_status", "order status", "orderstatus",
                "payment_state", "payment state", "paymentstate",
                "payment_status", "payment status", "paymentstatus"
        );

        for (String forbidden : forbiddenSubstrings) {
            if (kLower.contains(forbidden) || vLower.contains(forbidden)) {
                throw new BusinessException(ResultCode.VALIDATION_FAILED,
                        "Sensitive states (e.g., refund eligibility, order status, payment state) are not allowed in agent memory.");
            }
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
