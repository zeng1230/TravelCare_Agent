package travelcare_agent.retrieval;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.common.result.ResultCode;
import travelcare_agent.retrieval.entity.KnowledgeChunk;
import travelcare_agent.retrieval.entity.KnowledgeDocument;
import travelcare_agent.retrieval.repository.KnowledgeChunkRepository;
import travelcare_agent.retrieval.repository.KnowledgeDocumentRepository;
import travelcare_agent.retrieval.service.KnowledgeIngestionService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeIngestionServiceTest {

    @Test
    void convertsDatabaseUniqueKeyConflictToValidationFailure() {
        KnowledgeDocumentRepository documentRepository = new KnowledgeDocumentRepository() {
            @Override
            public KnowledgeDocument save(KnowledgeDocument document) {
                throw new DuplicateKeyException("Duplicate entry for uk_knowledge_documents_content_hash");
            }

            @Override
            public Optional<KnowledgeDocument> findById(Long id) {
                return Optional.empty();
            }

            @Override
            public Optional<KnowledgeDocument> findByContentHash(String contentHash) {
                return Optional.empty();
            }
        };

        KnowledgeChunkRepository chunkRepository = new KnowledgeChunkRepository() {
            @Override
            public KnowledgeChunk save(KnowledgeChunk chunk) {
                return chunk;
            }

            @Override
            public void saveBatch(List<KnowledgeChunk> chunks) {
            }

            @Override
            public List<KnowledgeChunk> searchFulltext(String queryText, LocalDateTime now, List<String> docTypes, int limit) {
                return List.of();
            }

            @Override
            public List<KnowledgeChunk> searchFallback(List<String> keywords, LocalDateTime now, List<String> docTypes, int limit) {
                return List.of();
            }
        };

        KnowledgeIngestionService service = new KnowledgeIngestionService(documentRepository, chunkRepository);

        assertThatThrownBy(() -> service.ingest(
                "Duplicate",
                "REFUND_SOP",
                "https://example.com/duplicate",
                "same raw content",
                null,
                null
        )).isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException businessException = (BusinessException) ex;
                    assertThat(businessException.getResultCode()).isEqualTo(ResultCode.VALIDATION_FAILED);
                    assertThat(businessException.getMessage()).contains("Duplicate document content");
                });
    }
}
