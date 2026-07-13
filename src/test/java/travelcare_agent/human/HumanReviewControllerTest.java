package travelcare_agent.human;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import travelcare_agent.api.HumanReviewController;
import travelcare_agent.audit.AuditService;
import travelcare_agent.common.exception.GlobalExceptionHandler;
import travelcare_agent.common.result.ResultCode;
import travelcare_agent.conversation.service.SessionEventService;
import travelcare_agent.conversation.entity.Session;
import travelcare_agent.conversation.repository.InMemorySessionRepository;
import travelcare_agent.enums.RefundCaseStatus;
import travelcare_agent.enums.HumanReviewCaseStatus;
import travelcare_agent.human.entity.HumanReviewCase;
import travelcare_agent.human.packet.HumanHandoffContextPacketBuilder;
import travelcare_agent.human.repository.InMemoryHumanReviewCaseRepository;
import travelcare_agent.human.service.HumanReviewService;
import travelcare_agent.security.AuthorizationService;
import travelcare_agent.security.CurrentUser;
import travelcare_agent.refund.repository.InMemoryRefundCaseRepository;
import travelcare_agent.refund.entity.RefundCase;
import travelcare_agent.workflow.entity.Workflow;
import travelcare_agent.workflow.repository.InMemoryWorkflowRepository;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HumanReviewControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private InMemoryHumanReviewCaseRepository hrRepo;
    private SessionEventService eventService;
    private AuditService auditService;
    private InMemoryWorkflowRepository workflowRepo;
    private InMemoryRefundCaseRepository refundRepo;
    private HumanReviewService humanReviewService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        hrRepo = new InMemoryHumanReviewCaseRepository();
        eventService = mock(SessionEventService.class);
        auditService = mock(AuditService.class);
        workflowRepo = new InMemoryWorkflowRepository();
        refundRepo = new InMemoryRefundCaseRepository();
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        Session session = Session.create("default", 1001L, "WEB");
        session.setId(100L);
        sessions.save(session);
        AuthorizationService authorizationService = mock(AuthorizationService.class);
        when(authorizationService.currentUser()).thenReturn(
                new CurrentUser(99L, "default", java.util.Set.of("OPERATOR")));
        saveWorkflowAndRefund(200L, 300L);
        saveWorkflowAndRefund(201L, 301L);
        humanReviewService = new HumanReviewService(hrRepo, eventService, auditService, workflowRepo, refundRepo, null,
                new HumanHandoffContextPacketBuilder(
                        sessions,
                        new travelcare_agent.conversation.repository.InMemorySessionEventRepository(),
                        workflowRepo,
                        new travelcare_agent.workflow.repository.InMemoryWorkflowStepRepository(),
                        refundRepo,
                        emptyTraceRuns(),
                        mock(travelcare_agent.trace.TraceQueryService.class),
                        new travelcare_agent.trace.RedactionService()),
                sessions,
                authorizationService);

        mockMvc = MockMvcBuilders.standaloneSetup(new HumanReviewController(humanReviewService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void testListOpenCases() throws Exception {
        humanReviewService.createCase(100L, 200L, 300L, "REFUND_REVIEW", "HIGH", "PAID_TIMEOUT", "{}");
        humanReviewService.createCase(100L, 201L, 301L, "REFUND_REVIEW", "LOW", "PAID_TIMEOUT", "{}");

        mockMvc.perform(get("/api/human-review/cases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].priority").value("HIGH"))
                .andExpect(jsonPath("$.data[1].priority").value("LOW"));
    }

    @Test
    void testGetCase() throws Exception {
        HumanReviewCase hrCase = humanReviewService.createCase(100L, 200L, 300L, "REFUND_REVIEW", "HIGH", "PAID_TIMEOUT", "{}");

        mockMvc.perform(get("/api/human-review/cases/{caseId}", hrCase.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.id").value(hrCase.getId().toString()))
                .andExpect(jsonPath("$.data.status").value(HumanReviewCaseStatus.OPEN.name()))
                .andExpect(jsonPath("$.data.contextPacket.packetVersion").value("PR-4D-v1"))
                .andExpect(jsonPath("$.data.contextPacket.sessionId").value(100));
    }

    private void saveWorkflowAndRefund(Long workflowId, Long refundId) {
        Workflow workflow = Workflow.create(100L, "order_refund_inquiry");
        workflow.setId(workflowId);
        workflowRepo.insert(workflow);
        RefundCase refund = RefundCase.create(1001L, 10L, workflowId, RefundCaseStatus.NEED_HUMAN,
                java.math.BigDecimal.TEN, "manual review", "{\"decision\":\"NEED_HUMAN\"}");
        refund.setId(refundId);
        refundRepo.insert(refund);
    }

    @Test
    void testGetCase_NotFound() throws Exception {
        mockMvc.perform(get("/api/human-review/cases/{caseId}", 9999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ResultCode.NOT_FOUND.code()));
    }

    @Test
    void testAssignCase() throws Exception {
        HumanReviewCase hrCase = humanReviewService.createCase(100L, 200L, 300L, "REFUND_REVIEW", "HIGH", "PAID_TIMEOUT", "{}");

        mockMvc.perform(post("/api/human-review/cases/{caseId}/assign", hrCase.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.status").value(HumanReviewCaseStatus.ASSIGNED.name()))
                .andExpect(jsonPath("$.data.assignedTo").value("99"));
    }

    @Test
    void assignRejectsAnyClientSuppliedIdentityOrOtherField() throws Exception {
        HumanReviewCase hrCase = humanReviewService.createCase(
                100L, 200L, 300L, "REFUND_REVIEW", "HIGH", "PAID_TIMEOUT", "{}");

        mockMvc.perform(post("/api/human-review/cases/{caseId}/assign", hrCase.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator_id\":\"spoofed\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ResultCode.VALIDATION_FAILED.code()));
        mockMvc.perform(post("/api/human-review/cases/{caseId}/assign", hrCase.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resolveRejectsUnknownResolutionAndIdentityField() throws Exception {
        HumanReviewCase hrCase = humanReviewService.createCase(
                100L, 200L, 300L, "REFUND_REVIEW", "HIGH", "PAID_TIMEOUT", "{}");

        mockMvc.perform(post("/api/human-review/cases/{caseId}/resolve", hrCase.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolution\":\"approved\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/human-review/cases/{caseId}/resolve", hrCase.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolution\":\"REJECTED\",\"operatorId\":\"spoofed\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testResolveCase() throws Exception {
        HumanReviewCase hrCase = humanReviewService.createCase(100L, 200L, 300L, "REFUND_REVIEW", "HIGH", "PAID_TIMEOUT", "{}");

        mockMvc.perform(post("/api/human-review/cases/{caseId}/resolve", hrCase.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolution\":\"REJECTED\",\"resolution_note\":\"Rejected manually.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.status").value(HumanReviewCaseStatus.RESOLVED.name()))
                .andExpect(jsonPath("$.data.resolution").value("REJECTED"))
                .andExpect(jsonPath("$.data.resolutionNote").value("Rejected manually."));
    }

    private static travelcare_agent.trace.repository.TraceRunRepository emptyTraceRuns() {
        travelcare_agent.trace.repository.TraceRunRepository repository =
                mock(travelcare_agent.trace.repository.TraceRunRepository.class);
        when(repository.findLatestBySessionIdAndWorkflowId(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Optional.empty());
        return repository;
    }
}
