package travelcare_agent.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import travelcare_agent.agentrun.entity.AgentRun;
import travelcare_agent.agentrun.service.AgentRunReplayService;
import travelcare_agent.agentrun.service.AgentRunService;
import travelcare_agent.common.result.PageResult;
import travelcare_agent.common.result.Result;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api")
public class AgentRunController {

    private final AgentRunService agentRunService;
    private final AgentRunReplayService agentRunReplayService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentRunController(AgentRunService agentRunService, AgentRunReplayService agentRunReplayService) {
        this.agentRunService = agentRunService;
        this.agentRunReplayService = agentRunReplayService;
    }

    @GetMapping("/agent-runs/{agentRunId}")
    public Result<AgentRunResponse> getAgentRun(@PathVariable Long agentRunId) {
        return Result.success(AgentRunResponse.from(agentRunService.getRun(agentRunId), objectMapper));
    }

    @GetMapping("/agent-runs/{agentRunId}/replay")
    public Result<AgentRunReplayService.AgentRunReplayResponse> replayAgentRun(@PathVariable Long agentRunId) {
        return Result.success(agentRunReplayService.replay(agentRunId));
    }

    @GetMapping("/sessions/{sessionId}/agent-runs")
    public Result<PageResult<AgentRunResponse>> listSessionAgentRuns(
            @PathVariable Long sessionId,
            @RequestParam(defaultValue = "1") Long pageNo,
            @RequestParam(defaultValue = "20") Long pageSize
    ) {
        AgentRunService.AgentRunPage page = agentRunService.listRunsBySession(sessionId, pageNo, pageSize);
        List<AgentRunResponse> records = page.records().stream()
                .map(run -> AgentRunResponse.from(run, objectMapper))
                .toList();
        return Result.success(PageResult.of(records, page.total(), page.pageNo(), page.pageSize()));
    }

    public record AgentRunResponse(
            Long id,
            Long sessionId,
            Long workflowId,
            Long taskId,
            String correlationId,
            String runType,
            String source,
            List<Long> inputEventIds,
            List<Long> retrievalChunkIds,
            List<Long> memoryIds,
            String workflowSnapshotJson,
            String promptVersion,
            String responseTemplateVersion,
            String contextHash,
            String contextSnapshotHash,
            String answerHash,
            Long outputEventId,
            String status,
            Long latencyMs,
            String errorCode,
            String errorMessage,
            String createdBy,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        static AgentRunResponse from(AgentRun run, ObjectMapper objectMapper) {
            return new AgentRunResponse(
                    run.getId(),
                    run.getSessionId(),
                    run.getWorkflowId(),
                    run.getTaskId(),
                    run.getCorrelationId(),
                    run.getRunType(),
                    run.getSource(),
                    parseIds(run.getInputEventIdsJson(), objectMapper),
                    parseIds(run.getRetrievalChunkIdsJson(), objectMapper),
                    parseIds(run.getMemoryIdsJson(), objectMapper),
                    run.getWorkflowSnapshotJson(),
                    run.getPromptVersion(),
                    run.getResponseTemplateVersion(),
                    run.getContextHash(),
                    run.getContextSnapshotHash(),
                    run.getAnswerHash(),
                    run.getOutputEventId(),
                    run.getStatus(),
                    run.getLatencyMs(),
                    run.getErrorCode(),
                    run.getErrorMessage(),
                    run.getCreatedBy(),
                    run.getCreatedAt(),
                    run.getUpdatedAt()
            );
        }

        private static List<Long> parseIds(String json, ObjectMapper objectMapper) {
            if (json == null || json.isBlank()) {
                return List.of();
            }
            try {
                return objectMapper.readValue(json, new TypeReference<>() {});
            } catch (Exception ex) {
                return List.of();
            }
        }
    }
}
