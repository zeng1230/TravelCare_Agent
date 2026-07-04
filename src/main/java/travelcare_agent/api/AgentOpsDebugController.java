package travelcare_agent.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import travelcare_agent.agentops.AgentOpsDebugRequest;
import travelcare_agent.agentops.AgentOpsDebugResponse;
import travelcare_agent.agentops.AgentOpsDebugService;
import travelcare_agent.common.result.Result;

@RestController
@RequestMapping("/api/agentops/debug")
public class AgentOpsDebugController {
    private final AgentOpsDebugService service;

    public AgentOpsDebugController(AgentOpsDebugService service) {
        this.service = service;
    }

    @PostMapping("/qa")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','EVALUATOR')")
    public Result<AgentOpsDebugResponse> qa(@RequestBody AgentOpsDebugRequest request) {
        return Result.success(service.debug(request));
    }
}
