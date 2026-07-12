package travelcare_agent.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import travelcare_agent.agent.ContextAssembler;
import travelcare_agent.api.AgentTraceController;
import travelcare_agent.api.AgentOpsDebugController;
import travelcare_agent.api.EvaluationController;
import travelcare_agent.api.HumanReviewController;
import travelcare_agent.api.SessionController;
import travelcare_agent.api.WorkflowController;
import travelcare_agent.agentops.AgentOpsDebugResponse;
import travelcare_agent.agentops.AgentOpsDebugService;
import travelcare_agent.agentops.DebugEvidenceMode;
import travelcare_agent.agentops.DebugFinalRoute;
import travelcare_agent.common.exception.GlobalExceptionHandler;
import travelcare_agent.conversation.entity.Session;
import travelcare_agent.conversation.repository.InMemorySessionRepository;
import travelcare_agent.conversation.repository.SessionRepository;
import travelcare_agent.conversation.service.SessionEventService;
import travelcare_agent.conversation.service.SessionService;
import travelcare_agent.dryrun.DiagnosticDryRunService;
import travelcare_agent.dryrun.TraceDiffService;
import travelcare_agent.evaluation.BaselinePromotionService;
import travelcare_agent.evaluation.EvaluationDatasetService;
import travelcare_agent.evaluation.EvaluationRunnerService;
import travelcare_agent.human.service.HumanReviewService;
import travelcare_agent.trace.TraceQueryService;
import travelcare_agent.workflow.repository.InMemoryWorkflowRepository;
import travelcare_agent.workflow.repository.WorkflowRepository;
import travelcare_agent.workflow.repository.WorkflowStepRepository;
import travelcare_agent.workflow.repository.WorkflowTaskRepository;
import travelcare_agent.refund.repository.RefundCaseRepository;
import travelcare_agent.human.repository.HumanReviewCaseRepository;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        SessionController.class,
        WorkflowController.class,
        AgentTraceController.class,
        AgentOpsDebugController.class,
        EvaluationController.class,
        HumanReviewController.class
})
@Import({
        GlobalExceptionHandler.class,
        SecurityConfig.class,
        JwtTokenService.class,
        JwtAuthenticationFilter.class,
        SecurityContextFacade.class,
        AuthorizationService.class,
        SecurityBoundaryWebMvcTest.TestBeans.class
})
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "travelcare.jwt.secret=" + SecurityTestTokenFactory.SECRET,
        "travelcare.jwt.issuer=travelcare-agent",
        "travelcare.security.dev-auth-enabled=true"
})
class SecurityBoundaryWebMvcTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void coreApiRequiresValidToken() throws Exception {
        mvc.perform(get("/api/sessions/1001/events"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/sessions/1001/events").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userCanCreateOwnSessionButCannotImpersonateAnotherUser() throws Exception {
        mvc.perform(post("/api/sessions")
                        .header("Authorization", SecurityTestTokenFactory.bearer(1001L, "tenant-a", "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"WEB\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionId").exists());

        mvc.perform(post("/api/sessions")
                        .header("Authorization", SecurityTestTokenFactory.bearer(1001L, "tenant-a", "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":2002,\"channel\":\"WEB\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void roleBoundariesDenyUserAndAllowDedicatedRoles() throws Exception {
        String user = SecurityTestTokenFactory.bearer(1001L, "tenant-a", "USER");
        mvc.perform(get("/api/agent-traces/trace-1").header("Authorization", user))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/agentops/debug/qa")
                        .header("Authorization", user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":1001,\"question\":\"退款规则是什么？\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/evaluation/runs/1").header("Authorization", user))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/human-review/cases").header("Authorization", user))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/agentops/debug/qa")
                        .header("Authorization", SecurityTestTokenFactory.bearer(1L, "tenant-a", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":1001,\"question\":\"退款规则是什么？\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/agentops/debug/qa")
                        .header("Authorization", SecurityTestTokenFactory.bearer(4004L, "tenant-a", "OPERATOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":1001,\"question\":\"退款规则是什么？\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/agentops/debug/qa")
                        .header("Authorization", SecurityTestTokenFactory.bearer(3003L, "tenant-a", "EVALUATOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":1001,\"question\":\"退款规则是什么？\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/evaluation/runs/1")
                        .header("Authorization", SecurityTestTokenFactory.bearer(3003L, "tenant-a", "EVALUATOR")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/human-review/cases")
                        .header("Authorization", SecurityTestTokenFactory.bearer(4004L, "tenant-a", "OPERATOR")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/agent-traces/trace-1")
                        .header("Authorization", SecurityTestTokenFactory.bearer(1L, "tenant-a", "ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void agentOpsDebugRequiresAuthentication() throws Exception {
        mvc.perform(post("/api/agentops/debug/qa")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":1001,\"question\":\"退款规则是什么？\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userCannotReadAnotherUsersSessionOrCrossTenantWorkflow() throws Exception {
        String userA = SecurityTestTokenFactory.bearer(1001L, "tenant-a", "USER");

        mvc.perform(get("/api/sessions/2002/events").header("Authorization", userA))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/workflows/3002").header("Authorization", userA))
                .andExpect(status().isForbidden());
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        SessionRepository sessionRepository() {
            InMemorySessionRepository repository = new InMemorySessionRepository();
            Session own = Session.create(1001L, "WEB");
            own.setId(1001L);
            own.setTenantId("tenant-a");
            repository.save(own);
            Session other = Session.create(2002L, "WEB");
            other.setId(2002L);
            other.setTenantId("tenant-b");
            repository.save(other);
            return repository;
        }

        @Bean
        WorkflowRepository workflowRepository() {
            InMemoryWorkflowRepository repository = new InMemoryWorkflowRepository();
            travelcare_agent.workflow.entity.Workflow other =
                    travelcare_agent.workflow.entity.Workflow.create(2002L, "ORDER_REFUND");
            other.setId(3002L);
            repository.insert(other);
            return repository;
        }

        @Bean SessionService sessionService() {
            SessionService service = mock(SessionService.class);
            when(service.createSession(1001L, "WEB"))
                    .thenReturn(new SessionService.CreateSessionResult(1234L, "ACTIVE"));
            when(service.listEvents(1001L)).thenReturn(java.util.List.of());
            when(service.listEvents(2002L)).thenReturn(java.util.List.of());
            return service;
        }
        @Bean SessionEventService sessionEventService() { return mock(SessionEventService.class); }
        @Bean ContextAssembler contextAssembler() { return mock(ContextAssembler.class); }
        @Bean TraceQueryService traceQueryService() {
            TraceQueryService service = mock(TraceQueryService.class);
            when(service.get("trace-1")).thenReturn(new TraceQueryService.TraceDetail(new travelcare_agent.trace.entity.TraceRun(), java.util.List.of(), java.util.List.of(), java.util.List.of()));
            return service;
        }
        @Bean DiagnosticDryRunService diagnosticDryRunService() { return mock(DiagnosticDryRunService.class); }
        @Bean TraceDiffService traceDiffService() { return mock(TraceDiffService.class); }
        @Bean AgentOpsDebugService agentOpsDebugService() {
            AgentOpsDebugService service = mock(AgentOpsDebugService.class);
            when(service.debug(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(new AgentOpsDebugResponse(
                            1001L, null, null, "DRY_RUN", DebugEvidenceMode.CURRENT_DIAGNOSTIC,
                            "mock", "mock", "stage10a-default",
                            "退款规则是什么？",
                            new AgentOpsDebugResponse.RetrievalDebug(java.util.List.of(), java.util.List.of(), java.util.List.of()),
                            new AgentOpsDebugResponse.AnswerabilityDebug("ANSWERABLE", "SUFFICIENT_CONTEXT"),
                            new AgentOpsDebugResponse.SafetyDebug("ALLOW", "SAFE", java.util.List.of()),
                            new AgentOpsDebugResponse.SupplierGatewayDebug(false, "dry-run mode"),
                            java.util.List.of(), DebugFinalRoute.ALLOW,
                            new AgentOpsDebugResponse.HumanHandoffRecommendation(false, "Automated answer allowed"),
                            java.util.List.of()));
            return service;
        }
        @Bean EvaluationDatasetService evaluationDatasetService() { return mock(EvaluationDatasetService.class); }
        @Bean EvaluationRunnerService evaluationRunnerService() {
            EvaluationRunnerService service = mock(EvaluationRunnerService.class);
            travelcare_agent.evaluation.entity.EvaluationRun run = new travelcare_agent.evaluation.entity.EvaluationRun();
            run.setId(1L);
            run.setDatasetId(1L);
            run.setDatasetVersion(1);
            run.setProviderMode("mock");
            run.setPromptStubVersion("test");
            run.setStatus("COMPLETED");
            run.setTotalCount(0);
            run.setPassedCount(0);
            run.setFailedCount(0);
            run.setErrorCount(0);
            run.setSkippedCount(0);
            when(service.get(1L)).thenReturn(run);
            return service;
        }
        @Bean BaselinePromotionService baselinePromotionService() { return mock(BaselinePromotionService.class); }
        @Bean HumanReviewService humanReviewService() { return mock(HumanReviewService.class); }
        @Bean WorkflowStepRepository workflowStepRepository() { return mock(WorkflowStepRepository.class); }
        @Bean WorkflowTaskRepository workflowTaskRepository() { return mock(WorkflowTaskRepository.class); }
        @Bean RefundCaseRepository refundCaseRepository() { return mock(RefundCaseRepository.class); }
        @Bean HumanReviewCaseRepository humanReviewCaseRepository() { return mock(HumanReviewCaseRepository.class); }
    }
}
