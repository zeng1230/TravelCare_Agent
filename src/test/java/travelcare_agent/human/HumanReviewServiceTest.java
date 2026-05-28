package travelcare_agent.human;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import travelcare_agent.audit.AuditService;
import travelcare_agent.conversation.service.SessionEventService;
import travelcare_agent.enums.HumanReviewCaseStatus;
import travelcare_agent.enums.RefundCaseStatus;
import travelcare_agent.enums.SessionEventRole;
import travelcare_agent.enums.WorkflowStatus;
import travelcare_agent.human.entity.HumanReviewCase;
import travelcare_agent.human.repository.InMemoryHumanReviewCaseRepository;
import travelcare_agent.human.service.HumanReviewService;
import travelcare_agent.refund.entity.RefundCase;
import travelcare_agent.refund.repository.InMemoryRefundCaseRepository;
import travelcare_agent.workflow.entity.Workflow;
import travelcare_agent.workflow.repository.InMemoryWorkflowRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HumanReviewServiceTest {

    private InMemoryHumanReviewCaseRepository hrCaseRepository;
    private SessionEventService eventService;
    private AuditService auditService;
    private InMemoryWorkflowRepository workflowRepository;
    private InMemoryRefundCaseRepository refundCaseRepository;
    private HumanReviewService humanReviewService;

    @BeforeEach
    void setUp() {
        hrCaseRepository = new InMemoryHumanReviewCaseRepository();
        eventService = mock(SessionEventService.class);
        auditService = mock(AuditService.class);
        workflowRepository = new InMemoryWorkflowRepository();
        refundCaseRepository = new InMemoryRefundCaseRepository();

        humanReviewService = new HumanReviewService(
                hrCaseRepository,
                eventService,
                auditService,
                workflowRepository,
                refundCaseRepository
        );
    }

    @Test
    void testCreateCase() {
        HumanReviewCase hrCase = humanReviewService.createCase(
                100L, 200L, 300L,
                "REFUND_REVIEW", "HIGH", "PAID_TIMEOUT", "{\"some\":\"evidence\"}"
        );

        assertThat(hrCase.getId()).isNotNull();
        assertThat(hrCase.getSessionId()).isEqualTo(100L);
        assertThat(hrCase.getWorkflowId()).isEqualTo(200L);
        assertThat(hrCase.getRefundCaseId()).isEqualTo(300L);
        assertThat(hrCase.getStatus()).isEqualTo(HumanReviewCaseStatus.OPEN);
        assertThat(hrCase.getPriority()).isEqualTo("HIGH");
        assertThat(hrCase.getReasonCode()).isEqualTo("PAID_TIMEOUT");
        assertThat(hrCase.getEvidenceJson()).isEqualTo("{\"some\":\"evidence\"}");

        verify(auditService).recordOperator(
                eq(100L), eq(200L), eq("CREATE"), eq("HUMAN_REVIEW_CASE"),
                eq(hrCase.getId()), eq("SYSTEM"), eq("system"), anyString(), anyString()
        );
    }

    @Test
    void testAssignCase() {
        HumanReviewCase hrCase = humanReviewService.createCase(
                100L, 200L, 300L,
                "REFUND_REVIEW", "HIGH", "PAID_TIMEOUT", "{}"
        );

        HumanReviewCase assignedCase = humanReviewService.assignCase(hrCase.getId(), "operator-45");

        assertThat(assignedCase.getStatus()).isEqualTo(HumanReviewCaseStatus.ASSIGNED);
        assertThat(assignedCase.getAssignedTo()).isEqualTo("operator-45");

        verify(auditService).recordOperator(
                eq(100L), eq(200L), eq("ASSIGN"), eq("HUMAN_REVIEW_CASE"),
                eq(hrCase.getId()), eq("OPERATOR"), eq("operator-45"), anyString(), anyString()
        );
    }

    @Test
    void testResolveCase_Approved() {
        // Setup workflow and refund case
        Workflow workflow = Workflow.create(100L, "order_refund_inquiry");
        workflow.setId(200L);
        workflow.setStatus(WorkflowStatus.NEED_HUMAN);
        workflowRepository.save(workflow);

        RefundCase refundCase = RefundCase.create(1001L, 10L, 200L, RefundCaseStatus.NEED_HUMAN, BigDecimal.TEN, "need manual check", "{}");
        refundCase.setId(300L);
        refundCaseRepository.save(refundCase);

        HumanReviewCase hrCase = humanReviewService.createCase(
                100L, 200L, 300L,
                "REFUND_REVIEW", "HIGH", "PAID_TIMEOUT", "{}"
        );

        HumanReviewCase resolvedCase = humanReviewService.resolveCase(
                hrCase.getId(), "APPROVED", "The refund looks correct, approved.", "operator-45"
        );

        assertThat(resolvedCase.getStatus()).isEqualTo(HumanReviewCaseStatus.RESOLVED);
        assertThat(resolvedCase.getResolution()).isEqualTo("APPROVED");
        assertThat(resolvedCase.getResolutionNote()).isEqualTo("The refund looks correct, approved.");
        assertThat(resolvedCase.getResolvedBy()).isEqualTo("operator-45");
        assertThat(resolvedCase.getResolvedAt()).isNotNull();

        // Check if event was appended to session event stream
        verify(eventService).appendMessage(
                eq(100L),
                eq(SessionEventRole.ASSISTANT),
                eq("The refund looks correct, approved."),
                contains(resolvedCase.getId().toString())
        );

        // Check if workflow status was updated to RESPONDED
        Optional<Workflow> updatedWorkflow = workflowRepository.findById(200L);
        assertThat(updatedWorkflow).isPresent();
        assertThat(updatedWorkflow.get().getStatus()).isEqualTo(WorkflowStatus.RESPONDED);

        // Check if refund case status was updated to ELIGIBLE
        Optional<RefundCase> updatedRefundCase = refundCaseRepository.findById(300L);
        assertThat(updatedRefundCase).isPresent();
        assertThat(updatedRefundCase.get().getStatus()).isEqualTo(RefundCaseStatus.ELIGIBLE);

        // Verify audit logging
        verify(auditService).recordOperator(
                eq(100L), eq(200L), eq("RESOLVE"), eq("HUMAN_REVIEW_CASE"),
                eq(hrCase.getId()), eq("OPERATOR"), eq("operator-45"), anyString(), anyString()
        );
    }

    @Test
    void testResolveCase_Rejected() {
        // Setup workflow and refund case
        Workflow workflow = Workflow.create(100L, "order_refund_inquiry");
        workflow.setId(200L);
        workflow.setStatus(WorkflowStatus.NEED_HUMAN);
        workflowRepository.save(workflow);

        RefundCase refundCase = RefundCase.create(1001L, 10L, 200L, RefundCaseStatus.NEED_HUMAN, BigDecimal.TEN, "need manual check", "{}");
        refundCase.setId(300L);
        refundCaseRepository.save(refundCase);

        HumanReviewCase hrCase = humanReviewService.createCase(
                100L, 200L, 300L,
                "REFUND_REVIEW", "HIGH", "PAID_TIMEOUT", "{}"
        );

        HumanReviewCase resolvedCase = humanReviewService.resolveCase(
                hrCase.getId(), "REJECTED", "Sorry, you cannot request refund.", "operator-45"
        );

        assertThat(resolvedCase.getStatus()).isEqualTo(HumanReviewCaseStatus.RESOLVED);
        assertThat(resolvedCase.getResolution()).isEqualTo("REJECTED");

        // Check if workflow status was updated to FAILED
        Optional<Workflow> updatedWorkflow = workflowRepository.findById(200L);
        assertThat(updatedWorkflow).isPresent();
        assertThat(updatedWorkflow.get().getStatus()).isEqualTo(WorkflowStatus.FAILED);

        // Check if refund case status was updated to INELIGIBLE
        Optional<RefundCase> updatedRefundCase = refundCaseRepository.findById(300L);
        assertThat(updatedRefundCase).isPresent();
        assertThat(updatedRefundCase.get().getStatus()).isEqualTo(RefundCaseStatus.INELIGIBLE);
    }
}
