package travelcare_agent.retrieval.repository;

import travelcare_agent.retrieval.entity.KnowledgeChunk;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface KnowledgeChunkRepository {
    KnowledgeChunk save(KnowledgeChunk chunk);

    default Optional<KnowledgeChunk> findById(Long id) {
        throw new UnsupportedOperationException("findById is not implemented");
    }

    void saveBatch(List<KnowledgeChunk> chunks);

    List<KnowledgeChunk> searchFulltext(String queryText, LocalDateTime now, List<String> docTypes, int limit);

    List<KnowledgeChunk> searchFallback(List<String> keywords, LocalDateTime now, List<String> docTypes, int limit);
}
