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
import travelcare_agent.enums.HumanReviewCaseStatus;
import travelcare_agent.human.entity.HumanReviewCase;
import travelcare_agent.human.repository.InMemoryHumanReviewCaseRepository;
import travelcare_agent.human.service.HumanReviewService;
import travelcare_agent.refund.repository.InMemoryRefundCaseRepository;
import travelcare_agent.workflow.repository.InMemoryWorkflowRepository;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.mock;
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
        humanReviewService = new HumanReviewService(hrRepo, eventService, auditService, workflowRepo, refundRepo);

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
                .andExpect(jsonPath("$.data.status").value(HumanReviewCaseStatus.OPEN.name()));
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
                        .content("{\"operator_id\":\"operator-99\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.status").value(HumanReviewCaseStatus.ASSIGNED.name()))
                .andExpect(jsonPath("$.data.assignedTo").value("operator-99"));
    }

    @Test
    void testResolveCase() throws Exception {
        HumanReviewCase hrCase = humanReviewService.createCase(100L, 200L, 300L, "REFUND_REVIEW", "HIGH", "PAID_TIMEOUT", "{}");

        mockMvc.perform(post("/api/human-review/cases/{caseId}/resolve", hrCase.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolution\":\"APPROVED\",\"resolution_note\":\"Approved manually.\",\"operator_id\":\"operator-99\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.status").value(HumanReviewCaseStatus.RESOLVED.name()))
                .andExpect(jsonPath("$.data.resolution").value("APPROVED"))
                .andExpect(jsonPath("$.data.resolutionNote").value("Approved manually."));
    }
}
