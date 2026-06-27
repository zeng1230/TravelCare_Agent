package travelcare_agent.workflow;

import java.time.LocalDateTime;

public record DeadLetterMessage(
        Long taskId,
        Long workflowId,
        Long toolCallId,
        String traceId,
        String failureCode,
        Integer attempts,
        String deadLetterReason,
        Long outboxEventId,
        LocalDateTime createdAt
) {
}
