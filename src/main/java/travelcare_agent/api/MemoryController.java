package travelcare_agent.api;

import org.springframework.web.bind.annotation.*;
import travelcare_agent.common.result.Result;
import travelcare_agent.enums.MemoryType;
import travelcare_agent.memory.entity.AgentMemory;
import travelcare_agent.memory.service.MemoryService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/memories")
public class MemoryController {

    private final MemoryService memoryService;

    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @GetMapping("/users/{userId}")
    public Result<List<AgentMemory>> getActiveMemories(@PathVariable Long userId) {
        List<AgentMemory> memories = memoryService.getActiveMemories(userId, null, 100);
        return Result.success(memories);
    }

    @PostMapping("/users/{userId}")
    public Result<AgentMemory> createManualMemory(
            @PathVariable Long userId,
            @RequestBody CreateManualMemoryRequest request
    ) {
        AgentMemory memory = memoryService.createMemory(
                userId,
                null,
                null,
                request.memoryType(),
                request.memoryKey(),
                request.memoryValue(),
                request.confidence(),
                null,
                request.expiresAt()
        );
        return Result.success(memory);
    }

    public record CreateManualMemoryRequest(
            MemoryType memoryType,
            String memoryKey,
            String memoryValue,
            BigDecimal confidence,
            LocalDateTime expiresAt
    ) {
    }
}
