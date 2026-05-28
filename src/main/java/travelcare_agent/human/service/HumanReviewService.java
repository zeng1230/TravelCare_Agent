package travelcare_agent.human.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import travelcare_agent.audit.AuditService;
import travelcare_agent.conversation.service.SessionEventService;
import travelcare_agent.enums.HumanReviewCaseStatus;
import travelcare_agent.enums.RefundCaseStatus;
import travelcare_agent.enums.SessionEventRole;
import travelcare_agent.enums.WorkflowStatus;
import travelcare_agent.human.entity.HumanReviewCase;
import travelcare_agent.human.repository.HumanReviewCaseRepository;
import travelcare_agent.refund.entity.RefundCase;
import travelcare_agent.refund.repository.RefundCaseRepository;
import travelcare_agent.workflow.entity.Workflow;
import travelcare_agent.workflow.repository.WorkflowRepository;

import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.common.result.ResultCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class HumanReviewService {

    private final HumanReviewCaseRepository repository;
    private final SessionEventService eventService;
    private final AuditService auditService;
    private final WorkflowRepository workflowRepository;
    private final RefundCaseRepository refundCaseRepository;

    public HumanReviewService(
            HumanReviewCaseRepository repository,
            SessionEventService eventService,
            AuditService auditService,
            WorkflowRepository workflowRepository,
            RefundCaseRepository refundCaseRepository
    ) {
        this.repository = repository;
        this.eventService = eventService;
        this.auditService = auditService;
        this.workflowRepository = workflowRepository;
        this.refundCaseRepository = refundCaseRepository;
    }

    @Transactional
    public HumanReviewCase createCase(
            Long sessionId,
            Long workflowId,
            Long refundCaseId,
            String caseType,
            String priority,
            String reasonCode,
            String evidenceJson
    ) {
        HumanReviewCase hrCase = new HumanReviewCase();
        hrCase.setSessionId(sessionId);
        hrCase.setWorkflowId(workflowId);
        hrCase.setRefundCaseId(refundCaseId);
        hrCase.setCaseType(caseType);
        hrCase.setStatus(HumanReviewCaseStatus.OPEN);
        hrCase.setPriority(priority);
        hrCase.setReasonCode(reasonCode);
        hrCase.setEvidenceJson(evidenceJson == null ? "{}" : evidenceJson);
        hrCase.setCreatedAt(LocalDateTime.now());
        hrCase.setUpdatedAt(LocalDateTime.now());

        hrCase = repository.save(hrCase);

        auditService.recordOperator(
                sessionId,
                workflowId,
                "CREATE",
                "HUMAN_REVIEW_CASE",
                hrCase.getId(),
                "SYSTEM",
                "system",
                "{\"status\":\"OPEN\"}",
                "{\"reasonCode\":\"" + reasonCode + "\"}"
        );

        return hrCase;
    }

    @Transactional
    public HumanReviewCase assignCase(Long caseId, String operatorId) {
        HumanReviewCase hrCase = repository.findById(caseId)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Human review case not found: " + caseId));

        hrCase.setStatus(HumanReviewCaseStatus.ASSIGNED);
        hrCase.setAssignedTo(operatorId);
        hrCase.setUpdatedAt(LocalDateTime.now());

        hrCase = repository.save(hrCase);

        auditService.recordOperator(
                hrCase.getSessionId(),
                hrCase.getWorkflowId(),
                "ASSIGN",
                "HUMAN_REVIEW_CASE",
                hrCase.getId(),
                "OPERATOR",
                operatorId,
                "{\"status\":\"ASSIGNED\",\"assignedTo\":\"" + operatorId + "\"}",
                "{}"
        );

        return hrCase;
    }

    @Transactional
    public HumanReviewCase resolveCase(Long caseId, String resolution, String resolutionNote, String operatorId) {
        HumanReviewCase hrCase = repository.findById(caseId)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Human review case not found: " + caseId));

        hrCase.setStatus(HumanReviewCaseStatus.RESOLVED);
        hrCase.setResolution(resolution);
        hrCase.setResolutionNote(resolutionNote);
        hrCase.setResolvedBy(operatorId);
        hrCase.setResolvedAt(LocalDateTime.now());
        hrCase.setUpdatedAt(LocalDateTime.now());

        hrCase = repository.save(hrCase);

        // Audit resolve action
        auditService.recordOperator(
                hrCase.getSessionId(),
                hrCase.getWorkflowId(),
                "RESOLVE",
                "HUMAN_REVIEW_CASE",
                hrCase.getId(),
                "OPERATOR",
                operatorId,
                "{\"status\":\"RESOLVED\",\"resolution\":\"" + resolution + "\"}",
                "{\"resolutionNote\":\"" + (resolutionNote == null ? "" : resolutionNote) + "\"}"
        );

        // User visibility check
        if (resolutionNote != null && !resolutionNote.trim().isEmpty()) {
            eventService.appendMessage(
                    hrCase.getSessionId(),
                    SessionEventRole.ASSISTANT,
                    resolutionNote,
                    "{\"source\":\"human_review\",\"caseId\":\"" + hrCase.getId() + "\"}"
            );
        }

        // Update corresponding workflow status
        Optional<Workflow> workflowOpt = workflowRepository.findById(hrCase.getWorkflowId());
        if (workflowOpt.isPresent()) {
            Workflow workflow = workflowOpt.get();
            WorkflowStatus targetStatus = "APPROVED".equalsIgnoreCase(resolution) ? WorkflowStatus.RESPONDED : WorkflowStatus.FAILED;
            workflow.transitionTo(targetStatus, "RESOLVED", "{\"resolution\":\"" + resolution + "\",\"note\":\"" + (resolutionNote == null ? "" : resolutionNote) + "\"}");
            workflowRepository.save(workflow);
        }

        // Update corresponding refund case status
        if (hrCase.getRefundCaseId() != null) {
            Optional<RefundCase> refundCaseOpt = refundCaseRepository.findById(hrCase.getRefundCaseId());
            if (refundCaseOpt.isPresent()) {
                RefundCase refundCase = refundCaseOpt.get();
                RefundCaseStatus targetStatus = "APPROVED".equalsIgnoreCase(resolution) ? RefundCaseStatus.ELIGIBLE : RefundCaseStatus.INELIGIBLE;
                refundCase.setStatus(targetStatus);
                refundCase.setUpdatedAt(LocalDateTime.now());
                refundCaseRepository.save(refundCase);
            }
        }

        return hrCase;
    }

    public List<HumanReviewCase> listOpenCases() {
        return repository.findByStatus(HumanReviewCaseStatus.OPEN);
    }

    public Optional<HumanReviewCase> getCase(Long caseId) {
        return repository.findById(caseId);
    }

    public Optional<HumanReviewCase> findByWorkflowId(Long workflowId) {
        return repository.findByWorkflowId(workflowId);
    }
}
