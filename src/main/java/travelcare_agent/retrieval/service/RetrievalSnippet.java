package travelcare_agent.retrieval.service;

import java.time.LocalDateTime;

public record RetrievalSnippet(
        String retrievalRunId,
        Long documentId,
        Long chunkId,
        String title,
        String content,
        String sourceUri,
        LocalDateTime effectiveFrom,
        LocalDateTime effectiveTo,
        double score
) {
    public RetrievalSnippet(Long documentId, Long chunkId, String title, String content, String sourceUri, double score) {
        this(null, documentId, chunkId, title, content, sourceUri, null, null, score);
    }
}
