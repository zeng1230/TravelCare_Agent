package travelcare_agent.answerability;

import java.time.LocalDateTime;

public record CitationMetadata(
        String retrievalRunId,
        Long chunkId,
        Long documentId,
        String title,
        String sourceUri,
        LocalDateTime effectiveFrom,
        LocalDateTime effectiveTo
) {
}
