package travelcare_agent.api;

import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import travelcare_agent.common.result.Result;
import travelcare_agent.enums.MemoryType;
import travelcare_agent.memory.entity.AgentMemory;
import travelcare_agent.memory.service.MemoryService;
import travelcare_agent.security.AuthorizationService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/memories")
public class MemoryController {

    private final MemoryService memoryService;
    private final AuthorizationService authorizationService;

    @Autowired
    public MemoryController(MemoryService memoryService, AuthorizationService authorizationService) {
        this.memoryService = memoryService;
        this.authorizationService = authorizationService;
    }

    public MemoryController(MemoryService memoryService) {
        this(memoryService, null);
    }

    @GetMapping("/users/{userId}")
    @PreAuthorize("hasAnyRole('USER','ADMIN')")
    public Result<List<AgentMemory>> getActiveMemories(@PathVariable Long userId) {
        if (authorizationService != null) {
            authorizationService.requireUserPathAccess(userId);
        }
        List<AgentMemory> memories = memoryService.getActiveMemories(userId, null, 100);
        return Result.success(memories);
    }

    @PostMapping("/users/{userId}")
    @PreAuthorize("hasAnyRole('USER','ADMIN')")
    public Result<AgentMemory> createManualMemory(
            @PathVariable Long userId,
            @RequestBody CreateManualMemoryRequest request
    ) {
        if (authorizationService != null) {
            authorizationService.requireUserPathAccess(userId);
        }
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
