package travelcare_agent.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.common.result.Result;
import travelcare_agent.common.result.ResultCode;
import travelcare_agent.human.entity.HumanReviewCase;
import travelcare_agent.human.service.HumanReviewService;

import java.util.List;

@RestController
@RequestMapping("/api/human-review/cases")
@PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
public class HumanReviewController {

    private final HumanReviewService humanReviewService;

    public HumanReviewController(HumanReviewService humanReviewService) {
        this.humanReviewService = humanReviewService;
    }

    @GetMapping
    public Result<List<HumanReviewCaseResponse>> listOpenCases() {
        return Result.success(humanReviewService.listOpenCases().stream()
                .map(this::response)
                .toList());
    }

    @GetMapping("/{caseId}")
    public Result<HumanReviewCaseResponse> getCase(@PathVariable Long caseId) {
        return Result.success(response(humanReviewService.getCase(caseId)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Case not found"))));
    }

    @PostMapping("/{caseId}/assign")
    public Result<HumanReviewCaseResponse> assignCase(
            @PathVariable Long caseId,
            @RequestBody AssignRequest request
    ) {
        String operatorId = request.getOperatorId();
        if (operatorId == null || operatorId.trim().isEmpty()) {
            return Result.fail(ResultCode.VALIDATION_FAILED, "operator_id is required");
        }
        return Result.success(response(humanReviewService.assignCase(caseId, operatorId)));
    }

    @PostMapping("/{caseId}/resolve")
    public Result<HumanReviewCaseResponse> resolveCase(
            @PathVariable Long caseId,
            @RequestBody ResolveRequest request
    ) {
        String operatorId = request.getOperatorId();
        String resolution = request.resolution();
        String resolutionNote = request.getResolutionNote();

        if (operatorId == null || operatorId.trim().isEmpty()) {
            return Result.fail(ResultCode.VALIDATION_FAILED, "operator_id is required");
        }
        if (resolution == null || resolution.trim().isEmpty()) {
            return Result.fail(ResultCode.VALIDATION_FAILED, "resolution is required");
        }

        return Result.success(response(humanReviewService.resolveCase(caseId, resolution, resolutionNote, operatorId)));
    }

    private HumanReviewCaseResponse response(HumanReviewCase hrCase) {
        return HumanReviewCaseResponse.from(hrCase, humanReviewService.contextPacket(hrCase));
    }

    public record AssignRequest(
            @JsonProperty("operator_id") String operatorIdSnake,
            String operatorId
    ) {
        public String getOperatorId() {
            return operatorId != null ? operatorId : operatorIdSnake;
        }
    }

    public record ResolveRequest(
            String resolution,
            @JsonProperty("resolution_note") String resolutionNoteSnake,
            String resolutionNote,
            @JsonProperty("operator_id") String operatorIdSnake,
            String operatorId
    ) {
        public String getResolutionNote() {
            return resolutionNote != null ? resolutionNote : resolutionNoteSnake;
        }

        public String getOperatorId() {
            return operatorId != null ? operatorId : operatorIdSnake;
        }
    }
}
