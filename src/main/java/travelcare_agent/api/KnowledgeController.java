package travelcare_agent.api;

import org.springframework.web.bind.annotation.*;
import travelcare_agent.common.result.Result;
import travelcare_agent.retrieval.entity.KnowledgeDocument;
import travelcare_agent.retrieval.service.KnowledgeIngestionService;
import travelcare_agent.retrieval.service.RetrievalQuery;
import travelcare_agent.retrieval.service.RetrievalService;
import travelcare_agent.retrieval.service.RetrievalSnippet;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeIngestionService ingestionService;
    private final RetrievalService retrievalService;

    public KnowledgeController(
            KnowledgeIngestionService ingestionService,
            RetrievalService retrievalService
    ) {
        this.ingestionService = ingestionService;
        this.retrievalService = retrievalService;
    }

    @PostMapping("/documents")
    public Result<KnowledgeDocument> ingestDocument(@RequestBody IngestDocumentRequest request) {
        KnowledgeDocument document = ingestionService.ingest(
                request.title(),
                request.docType(),
                request.sourceUri(),
                request.content(),
                request.effectiveFrom(),
                request.effectiveTo()
        );
        return Result.success(document);
    }

    @GetMapping("/search")
    public Result<List<RetrievalSnippet>> searchKnowledge(@RequestParam String query) {
        List<RetrievalSnippet> snippets = retrievalService.retrieve(
                new RetrievalQuery(null, null, query, null, 5)
        );
        return Result.success(snippets);
    }

    public record IngestDocumentRequest(
            String title,
            String docType,
            String sourceUri,
            String content,
            LocalDateTime effectiveFrom,
            LocalDateTime effectiveTo
    ) {
    }
}
