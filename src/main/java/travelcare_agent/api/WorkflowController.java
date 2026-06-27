package travelcare_agent.api;

import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.common.result.Result;
import travelcare_agent.common.result.ResultCode;
import travelcare_agent.human.entity.HumanReviewCase;
import travelcare_agent.human.repository.HumanReviewCaseRepository;
import travelcare_agent.refund.entity.RefundCase;
import travelcare_agent.refund.repository.RefundCaseRepository;
import travelcare_agent.workflow.entity.Workflow;
import travelcare_agent.workflow.entity.WorkflowStep;
import travelcare_agent.workflow.entity.WorkflowTask;
import travelcare_agent.workflow.repository.WorkflowRepository;
import travelcare_agent.workflow.repository.WorkflowStepRepository;
import travelcare_agent.workflow.repository.WorkflowTaskRepository;
import travelcare_agent.security.AuthorizationService;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api")
public class WorkflowController {

    private final WorkflowRepository workflowRepository;
    private final WorkflowStepRepository stepRepository;
    private final WorkflowTaskRepository taskRepository;
    private final RefundCaseRepository refundCaseRepository;
    private final HumanReviewCaseRepository hrCaseRepository;
    private final AuthorizationService authorizationService;

    @Autowired
    public WorkflowController(
            WorkflowRepository workflowRepository,
            WorkflowStepRepository stepRepository,
            WorkflowTaskRepository taskRepository,
            RefundCaseRepository refundCaseRepository,
            HumanReviewCaseRepository hrCaseRepository,
            AuthorizationService authorizationService
    ) {
        this.workflowRepository = workflowRepository;
        this.stepRepository = stepRepository;
        this.taskRepository = taskRepository;
        this.refundCaseRepository = refundCaseRepository;
        this.hrCaseRepository = hrCaseRepository;
        this.authorizationService = authorizationService;
    }

    public WorkflowController(
            WorkflowRepository workflowRepository,
            WorkflowStepRepository stepRepository,
            WorkflowTaskRepository taskRepository,
            RefundCaseRepository refundCaseRepository,
            HumanReviewCaseRepository hrCaseRepository
    ) {
        this(workflowRepository, stepRepository, taskRepository, refundCaseRepository, hrCaseRepository, null);
    }

    @GetMapping("/workflows/{workflowId}")
    @PreAuthorize("hasAnyRole('USER','OPERATOR','ADMIN')")
    public Result<WorkflowDetailResponse> getWorkflowDetail(@PathVariable Long workflowId) {
        if (authorizationService != null) {
            authorizationService.requireWorkflowAccess(workflowId);
        }
        Workflow workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Workflow not found: " + workflowId));
        return Result.success(buildDetailResponse(workflow));
    }

    @GetMapping("/workflows/{workflowId}/steps")
    @PreAuthorize("hasAnyRole('USER','OPERATOR','ADMIN')")
    public Result<List<WorkflowStepResponse>> getWorkflowSteps(@PathVariable Long workflowId) {
        if (authorizationService != null) {
            authorizationService.requireWorkflowAccess(workflowId);
        }
        // First verify that the workflow exists (throws 404 if not found)
        workflowRepository.findById(workflowId)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Workflow not found: " + workflowId));

        List<WorkflowStep> steps = stepRepository.findByWorkflowId(workflowId);
        List<WorkflowStepResponse> stepResponses = steps.stream()
                .map(WorkflowStepResponse::from)
                .toList();
        return Result.success(stepResponses);
    }

    @GetMapping("/sessions/{sessionId}/workflows")
    @PreAuthorize("hasAnyRole('USER','OPERATOR','ADMIN')")
    public Result<List<WorkflowDetailResponse>> getSessionWorkflows(@PathVariable Long sessionId) {
        if (authorizationService != null) {
            authorizationService.requireSessionAccess(sessionId);
        }
        List<Workflow> workflows = workflowRepository.findBySessionId(sessionId);
        List<WorkflowDetailResponse> responses = workflows.stream()
                .map(this::buildDetailResponse)
                .toList();
        return Result.success(responses);
    }

    private WorkflowDetailResponse buildDetailResponse(Workflow workflow) {
        Long taskId = taskRepository.findByWorkflowId(workflow.getId())
                .map(WorkflowTask::getId)
                .orElse(null);
        Long hrCaseId = hrCaseRepository.findByWorkflowId(workflow.getId())
                .map(HumanReviewCase::getId)
                .orElse(null);
        Long refundCaseId = refundCaseRepository.findByWorkflowId(workflow.getId())
                .map(RefundCase::getId)
                .orElse(null);
        return WorkflowDetailResponse.from(workflow, taskId, hrCaseId, refundCaseId);
    }

    public record WorkflowDetailResponse(
            Long id,
            Long sessionId,
            String workflowType,
            String status,
            String currentStep,
            String stateJson,
            Long taskId,
            Long humanReviewCaseId,
            Long refundCaseId,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        public static WorkflowDetailResponse from(
                Workflow workflow,
                Long taskId,
                Long humanReviewCaseId,
                Long refundCaseId
        ) {
            return new WorkflowDetailResponse(
                    workflow.getId(),
                    workflow.getSessionId(),
                    workflow.getWorkflowType(),
                    workflow.getStatus().name(),
                    workflow.getCurrentStep(),
                    workflow.getStateJson(),
                    taskId,
                    humanReviewCaseId,
                    refundCaseId,
                    workflow.getCreatedAt(),
                    workflow.getUpdatedAt()
            );
        }
    }

    public record WorkflowStepResponse(
            Long id,
            Long workflowId,
            String stepName,
            String status,
            String inputJson,
            String outputJson,
            String errorCode,
            LocalDateTime startedAt,
            LocalDateTime finishedAt
    ) {
        public static WorkflowStepResponse from(WorkflowStep step) {
            return new WorkflowStepResponse(
                    step.getId(),
                    step.getWorkflowId(),
                    step.getStepName(),
                    step.getStatus().name(),
                    step.getInputJson(),
                    step.getOutputJson(),
                    step.getErrorCode(),
                    step.getStartedAt(),
                    step.getFinishedAt()
            );
        }
    }
}
