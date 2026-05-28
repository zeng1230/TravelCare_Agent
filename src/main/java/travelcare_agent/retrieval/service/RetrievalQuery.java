package travelcare_agent.retrieval.service;

import java.util.List;

public record RetrievalQuery(
        Long sessionId,
        Long userId,
        String query,
        List<String> docTypes,
        int limit
) {
}
