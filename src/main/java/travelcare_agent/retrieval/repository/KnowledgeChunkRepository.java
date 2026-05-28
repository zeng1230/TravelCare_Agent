package travelcare_agent.retrieval.repository;

import travelcare_agent.retrieval.entity.KnowledgeChunk;
import java.time.LocalDateTime;
import java.util.List;

public interface KnowledgeChunkRepository {
    KnowledgeChunk save(KnowledgeChunk chunk);
    void saveBatch(List<KnowledgeChunk> chunks);
    List<KnowledgeChunk> searchFulltext(String queryText, LocalDateTime now, List<String> docTypes, int limit);
    List<KnowledgeChunk> searchFallback(List<String> keywords, LocalDateTime now, List<String> docTypes, int limit);
}
