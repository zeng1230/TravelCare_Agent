package travelcare_agent.api;

import travelcare_agent.enums.HumanReviewCaseStatus;
import travelcare_agent.human.entity.HumanReviewCase;
import travelcare_agent.human.packet.HumanHandoffContextPacket;

import java.time.LocalDateTime;

public record HumanReviewCaseResponse(
        Long id,
        Long sessionId,
        Long workflowId,
        Long refundCaseId,
        String caseType,
        HumanReviewCaseStatus status,
        String priority,
        String reasonCode,
        String evidenceJson,
        HumanHandoffContextPacket contextPacket,
        String assignedTo,
        String resolution,
        String resolutionNote,
        String resolvedBy,
        LocalDateTime resolvedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        boolean approvalAllowed
) {
    public static HumanReviewCaseResponse from(HumanReviewCase value, HumanHandoffContextPacket contextPacket) {
        return from(value, contextPacket, false);
    }

    public static HumanReviewCaseResponse from(HumanReviewCase value, HumanHandoffContextPacket contextPacket,
            boolean approvalAllowed) {
        return new HumanReviewCaseResponse(
                value.getId(),
                value.getSessionId(),
                value.getWorkflowId(),
                value.getRefundCaseId(),
                value.getCaseType(),
                value.getStatus(),
                value.getPriority(),
                value.getReasonCode(),
                value.getEvidenceJson(),
                contextPacket,
                value.getAssignedTo(),
                value.getResolution() == null ? null : value.getResolution().name(),
                value.getResolutionNote(),
                value.getResolvedBy(),
                value.getResolvedAt(),
                value.getCreatedAt(),
                value.getUpdatedAt(),
                approvalAllowed
        );
    }
}
