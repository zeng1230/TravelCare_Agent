package travelcare_agent.retrieval;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.common.result.ResultCode;
import travelcare_agent.retrieval.entity.KnowledgeDocument;
import travelcare_agent.retrieval.repository.mybatis.MyBatisKnowledgeDocumentMapper;
import travelcare_agent.retrieval.service.KnowledgeIngestionService;
import travelcare_agent.retrieval.service.RetrievalQuery;
import travelcare_agent.retrieval.service.RetrievalService;
import travelcare_agent.retrieval.service.RetrievalSnippet;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class KnowledgeRetrievalIntegrationTest {

    @Autowired
    private KnowledgeIngestionService ingestionService;

    @Autowired
    private RetrievalService retrievalService;

    @Autowired
    private MyBatisKnowledgeDocumentMapper documentMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void testIngestionAndParagraphChunking() {
        String content = "Paragraph One.\n\nParagraph Two.\n\nParagraph Three.";
        KnowledgeDocument doc = ingestionService.ingest(
                "Test Refund SOP",
                "REFUND_SOP",
                "https://example.com/sop/1",
                content,
                LocalDateTime.now().minusDays(1),
                LocalDateTime.now().plusDays(1)
        );

        assertThat(doc).isNotNull();
        assertThat(doc.getId()).isNotNull();
        assertThat(doc.getStatus()).isEqualTo("ACTIVE");
        assertThat(doc.getContentHash()).isNotBlank();

        // Query the ingested document chunks
        List<RetrievalSnippet> snippets = retrievalService.retrieve(
                new RetrievalQuery(1L, 1L, "Paragraph", Collections.singletonList("REFUND_SOP"), 5)
        );

        assertThat(snippets).hasSize(3);
        assertThat(snippets.get(0).documentId()).isEqualTo(doc.getId());
        assertThat(snippets.get(0).sourceUri()).isEqualTo("https://example.com/sop/1");
        assertThat(snippets).extracting(RetrievalSnippet::content)
                .containsExactlyInAnyOrder("Paragraph One.", "Paragraph Two.", "Paragraph Three.");
    }

    @Test
    void testIngestionPreventsDuplicatesByHash() {
        String content = "This is unique content to test hash prevention.";
        ingestionService.ingest(
                "Unique Doc",
                "REFUND_SOP",
                "https://example.com/unique",
                content,
                null,
                null
        );

        assertThatThrownBy(() -> ingestionService.ingest(
                "Duplicate Title But Same Content",
                "OTHER_DOC",
                "https://example.com/duplicate",
                content,
                null,
                null
        )).isInstanceOf(BusinessException.class)
          .satisfies(ex -> {
              BusinessException bu = (BusinessException) ex;
              assertThat(bu.getResultCode()).isEqualTo(ResultCode.VALIDATION_FAILED);
              assertThat(bu.getMessage()).contains("Duplicate document content");
          });
    }

    @Test
    void testContentHashHasDatabaseUniqueConstraint() {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'knowledge_documents'
                  AND index_name = 'uk_knowledge_documents_content_hash'
                  AND non_unique = 0
                  AND column_name = 'content_hash'
                """,
                Integer.class
        );

        assertThat(count).isEqualTo(1);
    }

    @Test
    void testActiveStatusAndDateFilters() {
        // Document A: Active
        KnowledgeDocument docActive = ingestionService.ingest(
                "Active SOP",
                "REFUND_SOP",
                "https://example.com/active",
                "Refund for delayed flight within active window.",
                LocalDateTime.now().minusDays(2),
                LocalDateTime.now().plusDays(2)
        );

        // Document B: Active but status changed to INACTIVE
        KnowledgeDocument docInactive = ingestionService.ingest(
                "Inactive SOP",
                "REFUND_SOP",
                "https://example.com/inactive",
                "Refund for delayed flight with status inactive.",
                null,
                null
        );
        docInactive.setStatus("INACTIVE");
        documentMapper.updateById(docInactive);

        // Document C: Expired
        ingestionService.ingest(
                "Expired SOP",
                "REFUND_SOP",
                "https://example.com/expired",
                "Refund for delayed flight within expired window.",
                LocalDateTime.now().minusDays(5),
                LocalDateTime.now().minusDays(2)
        );

        // Document D: Future
        ingestionService.ingest(
                "Future SOP",
                "REFUND_SOP",
                "https://example.com/future",
                "Refund for delayed flight within future window.",
                LocalDateTime.now().plusDays(2),
                LocalDateTime.now().plusDays(5)
        );

        // Run retrieve query
        List<RetrievalSnippet> snippets = retrievalService.retrieve(
                new RetrievalQuery(1L, 1L, "delayed flight", Collections.singletonList("REFUND_SOP"), 10)
        );

        // Should only match chunks from docActive
        assertThat(snippets)
                .extracting(RetrievalSnippet::documentId)
                .containsOnly(docActive.getId());
    }

    @Test
    void testWildcardFallbackMatching() {
        // Ingest a document containing unique keywords that are too short/specific for default MySQL FULLTEXT settings
        KnowledgeDocument doc = ingestionService.ingest(
                "Short Keyword SOP",
                "REFUND_SOP",
                "https://example.com/short",
                "Apply rule xyzabcqwe for special cases.",
                null,
                null
        );

        // Query with the specific fallback keyword
        List<RetrievalSnippet> snippets = retrievalService.retrieve(
                new RetrievalQuery(1L, 1L, "xyzabcqwe", Collections.singletonList("REFUND_SOP"), 5)
        );

        // Even if FULLTEXT ignores it or yields 0 matches, wildcard LIKE query fallback will fetch it
        assertThat(snippets).hasSize(1);
        assertThat(snippets.get(0).documentId()).isEqualTo(doc.getId());
        assertThat(snippets.get(0).content()).isEqualTo("Apply rule xyzabcqwe for special cases.");
        assertThat(snippets.get(0).title()).isEqualTo("Short Keyword SOP");
        assertThat(snippets.get(0).sourceUri()).isEqualTo("https://example.com/short");
    }
}
