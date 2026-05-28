package travelcare_agent.retrieval.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import travelcare_agent.retrieval.entity.KnowledgeChunk;
import travelcare_agent.retrieval.repository.KnowledgeChunkRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    private final KnowledgeChunkRepository chunkRepository;

    public RetrievalService(KnowledgeChunkRepository chunkRepository) {
        this.chunkRepository = chunkRepository;
    }

    public List<RetrievalSnippet> retrieve(RetrievalQuery query) {
        if (query == null || query.query() == null || query.query().trim().isEmpty()) {
            return new ArrayList<>();
        }

        String queryText = query.query().trim();
        List<String> docTypes = query.docTypes();
        int limit = query.limit() <= 0 ? 5 : query.limit();
        LocalDateTime now = LocalDateTime.now();

        // 1. Try MySQL FULLTEXT search first
        log.info("Executing FULLTEXT search query: '{}' with limit: {}", queryText, limit);
        List<KnowledgeChunk> chunks = chunkRepository.searchFulltext(queryText, now, docTypes, limit);

        // 2. Robust Search Fallback: if FULLTEXT yields zero results, fall back to programmatic wildcard LIKE matching
        if (chunks == null || chunks.isEmpty()) {
            log.info("FULLTEXT search returned zero results. Falling back to programmatic LIKE wildcard search.");
            List<String> keywords = Arrays.stream(queryText.split("\\s+"))
                    .map(String::trim)
                    .filter(kw -> !kw.isEmpty())
                    .collect(Collectors.toList());

            if (!keywords.isEmpty()) {
                chunks = chunkRepository.searchFallback(keywords, now, docTypes, limit);
            }
        }

        if (chunks == null) {
            return new ArrayList<>();
        }

        // 3. Map chunks to RetrievalSnippet including source citations metadata
        return chunks.stream()
                .map(chunk -> new RetrievalSnippet(
                        chunk.getDocumentId(),
                        chunk.getId(),
                        chunk.getTitle(),
                        chunk.getContent(),
                        chunk.getSourceUri(),
                        1.0 // Simple default score
                ))
                .collect(Collectors.toList());
    }
}
