package travelcare_agent.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import travelcare_agent.api.WorkflowController;
import travelcare_agent.common.exception.GlobalExceptionHandler;
import travelcare_agent.common.result.ResultCode;
import travelcare_agent.enums.HumanReviewCaseStatus;
import travelcare_agent.enums.RefundCaseStatus;
import travelcare_agent.enums.WorkflowStatus;
import travelcare_agent.enums.WorkflowStepStatus;
import travelcare_agent.human.entity.HumanReviewCase;
import travelcare_agent.human.repository.InMemoryHumanReviewCaseRepository;
import travelcare_agent.refund.entity.RefundCase;
import travelcare_agent.refund.repository.InMemoryRefundCaseRepository;
import travelcare_agent.workflow.entity.Workflow;
import travelcare_agent.workflow.entity.WorkflowStep;
import travelcare_agent.workflow.entity.WorkflowTask;
import travelcare_agent.workflow.repository.InMemoryWorkflowRepository;
import travelcare_agent.workflow.repository.InMemoryWorkflowStepRepository;
import travelcare_agent.workflow.repository.WorkflowTaskRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkflowControllerTest {

    private InMemoryWorkflowRepository workflowRepo;
    private InMemoryWorkflowStepRepository stepRepo;
    private WorkflowTaskRepository taskRepo;
    private InMemoryRefundCaseRepository refundRepo;
    private InMemoryHumanReviewCaseRepository hrRepo;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        workflowRepo = new InMemoryWorkflowRepository();
        stepRepo = new InMemoryWorkflowStepRepository();
        taskRepo = mock(WorkflowTaskRepository.class);
        refundRepo = new InMemoryRefundCaseRepository();
        hrRepo = new InMemoryHumanReviewCaseRepository();

        WorkflowController controller = new WorkflowController(
                workflowRepo,
                stepRepo,
                taskRepo,
                refundRepo,
                hrRepo
        );

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void testGetWorkflowDetail_Success() throws Exception {
        Workflow workflow = Workflow.create(100L, "ORDER_REFUND");
        workflow.setStatus(WorkflowStatus.NEED_HUMAN);
        workflow.setCurrentStep("EVALUATE_POLICY");
        workflow.setStateJson("{\"amount\":100}");
        workflowRepo.save(workflow);

        // Set up associations
        WorkflowTask mockTask = new WorkflowTask();
        mockTask.setId(999L);
        mockTask.setWorkflowId(workflow.getId());
        when(taskRepo.findByWorkflowId(workflow.getId())).thenReturn(Optional.of(mockTask));

        RefundCase refundCase = RefundCase.create(1L, 2L, workflow.getId(), RefundCaseStatus.NEED_HUMAN, new BigDecimal("100.00"), "reason", "{}");
        refundRepo.save(refundCase);

        HumanReviewCase hrCase = new HumanReviewCase();
        hrCase.setWorkflowId(workflow.getId());
        hrCase.setSessionId(100L);
        hrCase.setCaseType("REFUND_REVIEW");
        hrCase.setStatus(HumanReviewCaseStatus.OPEN);
        hrCase.setPriority("HIGH");
        hrCase.setReasonCode("TIMEOUT");
        hrCase.setEvidenceJson("{}");
        hrRepo.save(hrCase);

        mockMvc.perform(get("/api/workflows/{workflowId}", workflow.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.id").value(workflow.getId().toString()))
                .andExpect(jsonPath("$.data.sessionId").value("100"))
                .andExpect(jsonPath("$.data.workflowType").value("ORDER_REFUND"))
                .andExpect(jsonPath("$.data.status").value("NEED_HUMAN"))
                .andExpect(jsonPath("$.data.currentStep").value("EVALUATE_POLICY"))
                .andExpect(jsonPath("$.data.stateJson").value("{\"amount\":100}"))
                .andExpect(jsonPath("$.data.taskId").value(999))
                .andExpect(jsonPath("$.data.humanReviewCaseId").value(hrCase.getId().toString()))
                .andExpect(jsonPath("$.data.refundCaseId").value(refundCase.getId().toString()));
    }

    @Test
    void testGetWorkflowDetail_NotFound() throws Exception {
        mockMvc.perform(get("/api/workflows/{workflowId}", 8888L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ResultCode.NOT_FOUND.code()));
    }

    @Test
    void testGetWorkflowSteps_Success() throws Exception {
        Workflow workflow = Workflow.create(100L, "ORDER_REFUND");
        workflowRepo.save(workflow);

        WorkflowStep step1 = WorkflowStep.start(workflow.getId(), "INIT", "{}");
        step1.succeed("{\"result\":\"ok\"}");
        stepRepo.save(step1);

        WorkflowStep step2 = WorkflowStep.start(workflow.getId(), "CHECK_ORDER", "{\"id\":2}");
        stepRepo.save(step2);

        mockMvc.perform(get("/api/workflows/{workflowId}/steps", workflow.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].stepName").value("INIT"))
                .andExpect(jsonPath("$.data[0].status").value(WorkflowStepStatus.SUCCESS.name()))
                .andExpect(jsonPath("$.data[1].stepName").value("CHECK_ORDER"))
                .andExpect(jsonPath("$.data[1].status").value(WorkflowStepStatus.RUNNING.name()));
    }

    @Test
    void testGetWorkflowSteps_NotFound() throws Exception {
        mockMvc.perform(get("/api/workflows/{workflowId}/steps", 8888L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ResultCode.NOT_FOUND.code()));
    }

    @Test
    void testGetSessionWorkflows_Success() throws Exception {
        Workflow workflow1 = Workflow.create(101L, "ORDER_REFUND");
        workflowRepo.save(workflow1);

        Workflow workflow2 = Workflow.create(101L, "ORDER_REFUND");
        workflowRepo.save(workflow2);

        Workflow workflow3 = Workflow.create(102L, "ORDER_REFUND");
        workflowRepo.save(workflow3);

        mockMvc.perform(get("/api/sessions/{sessionId}/workflows", 101L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].sessionId").value("101"))
                .andExpect(jsonPath("$.data[1].sessionId").value("101"));
    }
}
