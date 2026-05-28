package travelcare_agent.retrieval.repository;

import travelcare_agent.retrieval.entity.KnowledgeDocument;
import java.util.Optional;

public interface KnowledgeDocumentRepository {
    KnowledgeDocument save(KnowledgeDocument document);
    Optional<KnowledgeDocument> findById(Long id);
    Optional<KnowledgeDocument> findByContentHash(String contentHash);
}
