package travelcare_agent.api;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import travelcare_agent.common.exception.GlobalExceptionHandler;
import travelcare_agent.trace.TraceQueryService;
import travelcare_agent.trace.entity.TraceRun;
import travelcare_agent.dryrun.*;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentTraceControllerTest {
    @Test
    void exposesDetailSessionAndDiagnosticsEndpoints() throws Exception {
        TraceQueryService service=mock(TraceQueryService.class); TraceRun run=new TraceRun();run.setTraceId("trace-1");run.setSessionId(10L);run.setStatus("SUCCEEDED");
        when(service.get("trace-1")).thenReturn(new TraceQueryService.TraceDetail(run,List.of(),List.of(),List.of()));
        when(service.bySession(10L,1,20)).thenReturn(new TraceQueryService.TracePage(List.of(run),1,1,20));
        when(service.diagnostics("trace-1")).thenReturn(new TraceQueryService.TraceDiagnostics("trace-1","SUCCEEDED","mock","mock-model","v1",List.of(),List.of(),List.of(),List.of(),List.of(),null,new TraceQueryService.RedactionSummary(0),List.of(),false));
        DiagnosticDryRunService dryRunService=mock(DiagnosticDryRunService.class);
        TraceDiffService diffService=mock(TraceDiffService.class);
        when(dryRunService.run(eq("trace-1"), any())).thenReturn(new DryRunResult(
                "DRY_RUN_NOT_READY","REJECTED","trace-1",null,null,null,null,"dry run is not ready",
                List.of("TOOL_RESULT"),List.of("VIEW_TRACE","VIEW_DIAGNOSTICS")));
        when(diffService.get("trace-1","dry-1")).thenReturn(new TraceDiffResult(
                1L,"trace-1","dry-1",false,"NONE",List.of(),java.util.Map.of(),java.util.Map.of(),"No differences"));
        MockMvc mvc=MockMvcBuilders.standaloneSetup(new AgentTraceController(service,dryRunService,diffService)).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/api/agent-traces/trace-1")).andExpect(status().isOk()).andExpect(jsonPath("$.data.run.traceId").value("trace-1"));
        mvc.perform(get("/api/agent-traces/by-session/10")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get("/api/agent-traces/trace-1/diagnostics")).andExpect(status().isOk()).andExpect(jsonPath("$.data.provider").value("mock"));
        mvc.perform(post("/api/agent-traces/trace-1/dry-run")
                        .contentType("application/json")
                        .content("{\"reason\":\"manual-debug\",\"providerMode\":\"mock\",\"compareAfterRun\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("DRY_RUN_NOT_READY"))
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.dryRunTraceId").doesNotExist())
                .andExpect(jsonPath("$.data.allowedActions[0]").value("VIEW_TRACE"))
                .andExpect(jsonPath("$.data.allowedActions[1]").value("VIEW_DIAGNOSTICS"));
        mvc.perform(get("/api/agent-traces/trace-1/diffs/dry-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.riskLevel").value("NONE"));
    }
}
