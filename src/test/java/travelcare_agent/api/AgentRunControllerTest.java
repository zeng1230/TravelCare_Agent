package travelcare_agent.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import travelcare_agent.agentrun.entity.AgentRun;
import travelcare_agent.agentrun.service.AgentRunReplayService;
import travelcare_agent.agentrun.service.AgentRunService;
import travelcare_agent.common.exception.GlobalExceptionHandler;
import travelcare_agent.common.result.ResultCode;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentRunControllerTest {

    private AgentRunService agentRunService;
    private AgentRunReplayService agentRunReplayService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        agentRunService = mock(AgentRunService.class);
        agentRunReplayService = mock(AgentRunReplayService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AgentRunController(agentRunService, agentRunReplayService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void getAgentRunReturnsCoreTraceFields() throws Exception {
        AgentRun run = sampleRun();
        when(agentRunService.getRun(1L)).thenReturn(run);

        mockMvc.perform(get("/api/agent-runs/{agentRunId}", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.id").value(1L))
                .andExpect(jsonPath("$.data.sessionId").value(100L))
                .andExpect(jsonPath("$.data.workflowId").value(200L))
                .andExpect(jsonPath("$.data.taskId").value(300L))
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.inputEventIds[0]").value(11L))
                .andExpect(jsonPath("$.data.retrievalChunkIds[0]").value(21L))
                .andExpect(jsonPath("$.data.memoryIds[0]").value(31L))
                .andExpect(jsonPath("$.data.outputEventId").value(41L));
    }

    @Test
    void listSessionAgentRunsUsesPageResult() throws Exception {
        when(agentRunService.listRunsBySession(100L, 1L, 20L))
                .thenReturn(new AgentRunService.AgentRunPage(List.of(sampleRun()), 1L, 1L, 20L));

        mockMvc.perform(get("/api/sessions/{sessionId}/agent-runs", 100L)
                        .param("pageNo", "1")
                        .param("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1L))
                .andExpect(jsonPath("$.data.pageNo").value(1L))
                .andExpect(jsonPath("$.data.pageSize").value(20L))
                .andExpect(jsonPath("$.data.records[0].id").value(1L));
    }

    @Test
    void replayAgentRunReturnsAggregatedTrace() throws Exception {
        when(agentRunReplayService.replay(1L)).thenReturn(new AgentRunReplayService.AgentRunReplayResponse(
                AgentRunReplayService.AgentRunReplaySummary.from(sampleRun()),
                List.of(),
                List.of(),
                List.of(),
                new AgentRunReplayService.WorkflowReplay("{\"status\":\"RESPONDED\"}", null),
                null,
                List.of(),
                List.of(),
                List.of()
        ));

        mockMvc.perform(get("/api/agent-runs/{agentRunId}/replay", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.agentRun.id").value(1L))
                .andExpect(jsonPath("$.data.workflowSnapshot.agentRunSnapshotJson").value("{\"status\":\"RESPONDED\"}"));
    }

    private static AgentRun sampleRun() {
        AgentRun run = new AgentRun();
        run.setId(1L);
        run.setSessionId(100L);
        run.setWorkflowId(200L);
        run.setTaskId(300L);
        run.setCorrelationId("corr-001");
        run.setRunType("SYNC_REPLY");
        run.setSource("session_service");
        run.setInputEventIdsJson("[11]");
        run.setRetrievalChunkIdsJson("[21]");
        run.setMemoryIdsJson("[31]");
        run.setWorkflowSnapshotJson("{\"status\":\"RESPONDED\"}");
        run.setPromptVersion("mock-agent-v1");
        run.setResponseTemplateVersion("refund-inquiry-template-v1");
        run.setContextHash("a".repeat(64));
        run.setContextSnapshotHash("a".repeat(64));
        run.setAnswerHash("b".repeat(64));
        run.setOutputEventId(41L);
        run.setStatus("SUCCEEDED");
        run.setLatencyMs(12L);
        run.setCreatedBy("SYSTEM");
        run.setCreatedAt(LocalDateTime.now());
        run.setUpdatedAt(LocalDateTime.now());
        return run;
    }
}
