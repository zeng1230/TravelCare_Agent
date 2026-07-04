package travelcare_agent.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import travelcare_agent.agentops.*;
import travelcare_agent.common.exception.GlobalExceptionHandler;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentOpsDebugControllerTest {

    @Test
    void returnsDebugExplanationUsingStandardResultEnvelope() throws Exception {
        AgentOpsDebugService service = mock(AgentOpsDebugService.class);
        when(service.debug(any())).thenReturn(new AgentOpsDebugResponse(
                10L, 20L, "trace-1", "DRY_RUN", "mock", "mock", "stage10a-default",
                "订单 ORD-1001 可以退款吗？",
                new AgentOpsDebugResponse.RetrievalDebug(List.of(), List.of(), List.of()),
                new AgentOpsDebugResponse.AnswerabilityDebug("ANSWERABLE", "SUFFICIENT_CONTEXT"),
                new AgentOpsDebugResponse.SafetyDebug("ALLOW", "SAFE", List.of()),
                new AgentOpsDebugResponse.SupplierGatewayDebug(false, "dry-run mode"),
                List.of(), DebugFinalRoute.ALLOW,
                new AgentOpsDebugResponse.HumanHandoffRecommendation(false, "Automated answer allowed"),
                List.of()
        ));
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new AgentOpsDebugController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mvc.perform(post("/api/agentops/debug/qa")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":10,\"workflowId\":20,\"question\":\"订单 ORD-1001 可以退款吗？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data.traceId").value("trace-1"))
                .andExpect(jsonPath("$.data.finalRoute").value("ALLOW"));
    }

    @Test
    void rejectsMissingSessionIdAndBlankQuestion() throws Exception {
        AgentOpsDebugService service = new AgentOpsDebugService(null, null, null, null, null, null, null);
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new AgentOpsDebugController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mvc.perform(post("/api/agentops/debug/qa")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"退款规则是什么？\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mvc.perform(post("/api/agentops/debug/qa")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":10,\"question\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
