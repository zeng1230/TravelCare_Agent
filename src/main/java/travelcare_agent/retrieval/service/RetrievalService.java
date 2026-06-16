package travelcare_agent.retrieval.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import travelcare_agent.retrieval.entity.KnowledgeChunk;
import travelcare_agent.retrieval.entity.KnowledgeDocument;
import travelcare_agent.retrieval.repository.KnowledgeChunkRepository;
import travelcare_agent.retrieval.repository.KnowledgeDocumentRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import travelcare_agent.trace.*;

@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    private final KnowledgeChunkRepository chunkRepository;
    private final KnowledgeDocumentRepository documentRepository;
    private final TraceService traceService;

    public RetrievalService(KnowledgeChunkRepository chunkRepository) {
        this(chunkRepository, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RetrievalService(KnowledgeChunkRepository chunkRepository, KnowledgeDocumentRepository documentRepository,
            TraceService traceService) {
        this.chunkRepository = chunkRepository;
        this.documentRepository = documentRepository;
        this.traceService = traceService;
    }

    public RetrievalService(KnowledgeChunkRepository chunkRepository, TraceService traceService) {
        this(chunkRepository, null, traceService);
    }

    public List<RetrievalSnippet> retrieve(RetrievalQuery query) {
        if (query == null || query.query() == null || query.query().trim().isEmpty()) {
            return new ArrayList<>();
        }

        String queryText = query.query().trim();
        String retrievalRunId = UUID.randomUUID().toString();
        TraceService.SpanHandle span = traceService == null ? TraceService.SpanHandle.unavailable()
                : traceService.startSpan(SpanType.RETRIEVAL, "knowledge-retrieval",
                Map.of("query", queryText, "retrievalRunId", retrievalRunId));
        List<String> docTypes = query.docTypes();
        int limit = query.limit() <= 0 ? 5 : query.limit();
        LocalDateTime now = LocalDateTime.now();

        // 1. Try MySQL FULLTEXT search first
        log.info("Executing FULLTEXT search query: '{}' with limit: {}", queryText, limit);
        List<KnowledgeChunk> chunks = chunkRepository.searchFulltext(queryText, now, docTypes, limit);

        // 2. Robust Search Fallback: if FULLTEXT yields zero results, fall back to programmatic wildcard LIKE matching
        if (chunks == null || chunks.isEmpty()) {
            if (traceService != null) traceService.recordEvent(span.traceId(), span.spanId(), TraceEventType.FALLBACK,
                    "fulltext-to-like", Map.of());
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
            if (traceService != null) traceService.recordCurrentSnapshot(TraceSnapshotType.RETRIEVAL_SUMMARY,
                    "RETRIEVAL_QUERY", null, Map.of("query", queryText, "retrievalRunId", retrievalRunId, "results", List.of()));
            if (traceService != null) traceService.finishSpanSuccess(span, null, Map.of("hitCount", 0));
            return new ArrayList<>();
        }

        // 3. Map chunks to RetrievalSnippet including source citations metadata
        List<RetrievalSnippet> result = chunks.stream()
                .map(chunk -> snippet(retrievalRunId, chunk))
                .collect(Collectors.toList());
        if (traceService != null) traceService.recordCurrentSnapshot(TraceSnapshotType.RETRIEVAL_SUMMARY,
                "RETRIEVAL_QUERY", null, Map.of(
                        "query", queryText,
                        "retrievalRunId", retrievalRunId,
                        "results", result.stream().map(RetrievalService::snapshotSummary).toList()
                ));
        if (traceService != null) traceService.finishSpanSuccess(span, null, Map.of(
                "hitCount", result.size(), "chunkIds", result.stream().map(RetrievalSnippet::chunkId).toList()));
        return result;
    }

    private RetrievalSnippet snippet(String retrievalRunId, KnowledgeChunk chunk) {
        Optional<KnowledgeDocument> document = documentRepository == null
                ? Optional.empty()
                : documentRepository.findById(chunk.getDocumentId());
        return new RetrievalSnippet(
                retrievalRunId,
                chunk.getDocumentId(),
                chunk.getId(),
                chunk.getTitle(),
                chunk.getContent(),
                chunk.getSourceUri(),
                document.map(KnowledgeDocument::getEffectiveFrom).orElse(null),
                document.map(KnowledgeDocument::getEffectiveTo).orElse(null),
                1.0
        );
    }

    private static Map<String, Object> snapshotSummary(RetrievalSnippet value) {
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("retrievalRunId", value.retrievalRunId());
        summary.put("documentId", value.documentId());
        summary.put("chunkId", value.chunkId());
        summary.put("title", value.title());
        summary.put("sourceUri", value.sourceUri());
        summary.put("effectiveFrom", value.effectiveFrom());
        summary.put("effectiveTo", value.effectiveTo());
        summary.put("score", value.score());
        return summary;
    }
}
