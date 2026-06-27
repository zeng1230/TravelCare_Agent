package travelcare_agent.api;

import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.core.env.Environment;
import travelcare_agent.answerability.AnswerabilityDecision;
import travelcare_agent.answerability.AnswerabilityRequest;
import travelcare_agent.answerability.AnswerabilityService;
import travelcare_agent.answerability.BusinessDecisionContext;
import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.common.result.Result;
import travelcare_agent.common.result.ResultCode;
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
    private final AnswerabilityService answerabilityService;
    private final Environment environment;

    public KnowledgeController(
            KnowledgeIngestionService ingestionService,
            RetrievalService retrievalService,
            AnswerabilityService answerabilityService,
            Environment environment
    ) {
        this.ingestionService = ingestionService;
        this.retrievalService = retrievalService;
        this.answerabilityService = answerabilityService;
        this.environment = environment;
    }

    @PostMapping("/documents")
    @PreAuthorize("hasRole('ADMIN')")
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
    @PreAuthorize("hasAnyRole('USER','OPERATOR','EVALUATOR','ADMIN')")
    public Result<List<RetrievalSnippet>> searchKnowledge(@RequestParam String query) {
        List<RetrievalSnippet> snippets = retrievalService.retrieve(
                new RetrievalQuery(null, null, query, null, 5)
        );
        return Result.success(snippets);
    }

    @PostMapping("/answerability/check")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<AnswerabilityCheckResponse> checkAnswerability(@RequestBody AnswerabilityCheckRequest request) {
        requireDebugProfile();
        int limit = request.limit() == null || request.limit() <= 0 ? 5 : request.limit();
        List<RetrievalSnippet> snippets = retrievalService.retrieve(
                new RetrievalQuery(null, null, request.query(), request.docTypes(), limit)
        );
        AnswerabilityDecision decision = answerabilityService.assess(new AnswerabilityRequest(
                request.query(),
                snippets,
                request.intent(),
                request.workflowType(),
                BusinessDecisionContext.none(),
                LocalDateTime.now()
        ));
        return Result.success(new AnswerabilityCheckResponse(snippets, decision));
    }

    private void requireDebugProfile() {
        for (String profile : environment.getActiveProfiles()) {
            if ("local".equalsIgnoreCase(profile) || "dev".equalsIgnoreCase(profile) || "test".equalsIgnoreCase(profile)) {
                return;
            }
        }
        throw new BusinessException(ResultCode.FORBIDDEN, "Answerability debug endpoint is only available in local/dev/test profiles");
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

    public record AnswerabilityCheckRequest(
            String query,
            List<String> docTypes,
            Integer limit,
            String intent,
            String workflowType
    ) {
    }

    public record AnswerabilityCheckResponse(
            List<RetrievalSnippet> retrievalResults,
            AnswerabilityDecision answerabilityDecision
    ) {
    }
}
