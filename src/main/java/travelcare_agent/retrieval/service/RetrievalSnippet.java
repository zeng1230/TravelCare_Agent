package travelcare_agent.retrieval.service;

public record RetrievalSnippet(
        Long documentId,
        Long chunkId,
        String title,
        String content,
        String sourceUri,
        double score
) {
}
