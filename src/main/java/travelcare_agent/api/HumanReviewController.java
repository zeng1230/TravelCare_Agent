package travelcare_agent.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.common.result.Result;
import travelcare_agent.common.result.ResultCode;
import travelcare_agent.human.entity.HumanReviewCase;
import travelcare_agent.human.packet.HumanHandoffContextPacket;
import travelcare_agent.human.service.HumanReviewService;
import travelcare_agent.enums.HumanReviewResolution;

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
            @RequestBody(required = false) JsonNode request
    ) {
        if (request != null && (!request.isObject() || !request.isEmpty())) {
            throw new BusinessException(ResultCode.VALIDATION_FAILED, "Assign request must be an empty object");
        }
        return Result.success(response(humanReviewService.assignCase(caseId)));
    }

    @PostMapping("/{caseId}/resolve")
    public Result<HumanReviewCaseResponse> resolveCase(
            @PathVariable Long caseId,
            @RequestBody JsonNode request
    ) {
        ResolveCommand command = parseResolveRequest(request);
        return Result.success(response(humanReviewService.resolveCase(
                caseId, command.resolution(), command.resolutionNote())));
    }

    private HumanReviewCaseResponse response(HumanReviewCase hrCase) {
        HumanHandoffContextPacket packet = humanReviewService.contextPacket(hrCase);
        boolean approvalAllowed = hrCase.getStatus() == travelcare_agent.enums.HumanReviewCaseStatus.RESOLVED
                && hrCase.getResolution() == HumanReviewResolution.APPROVED
                ? travelcare_agent.human.service.HumanReviewApprovalPolicy.hasAuthoritativeApprovalEvidence(packet)
                : travelcare_agent.human.service.HumanReviewApprovalPolicy.allows(hrCase, packet);
        return HumanReviewCaseResponse.from(hrCase, packet, approvalAllowed);
    }

    private ResolveCommand parseResolveRequest(JsonNode request) {
        if (request == null || !request.isObject()) {
            throw new BusinessException(ResultCode.VALIDATION_FAILED, "Resolve request must be an object");
        }
        java.util.Set<String> allowed = java.util.Set.of("resolution", "resolutionNote", "resolution_note");
        java.util.Iterator<String> names = request.fieldNames();
        while (names.hasNext()) {
            if (!allowed.contains(names.next())) {
                throw new BusinessException(ResultCode.VALIDATION_FAILED, "Unknown resolve request field");
            }
        }
        JsonNode resolutionNode = request.get("resolution");
        if (resolutionNode == null || !resolutionNode.isTextual()) {
            throw new BusinessException(ResultCode.VALIDATION_FAILED, "resolution is required");
        }
        HumanReviewResolution resolution;
        try {
            resolution = HumanReviewResolution.valueOf(resolutionNode.textValue());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ResultCode.VALIDATION_FAILED, "resolution is invalid");
        }
        if (request.has("resolutionNote") && request.has("resolution_note")) {
            throw new BusinessException(ResultCode.VALIDATION_FAILED, "resolution note must be provided once");
        }
        JsonNode noteNode = request.has("resolutionNote") ? request.get("resolutionNote") : request.get("resolution_note");
        if (noteNode != null && !noteNode.isNull() && !noteNode.isTextual()) {
            throw new BusinessException(ResultCode.VALIDATION_FAILED, "resolution note must be text");
        }
        return new ResolveCommand(resolution, noteNode == null || noteNode.isNull() ? null : noteNode.textValue());
    }

    private record ResolveCommand(HumanReviewResolution resolution, String resolutionNote) {}
}
