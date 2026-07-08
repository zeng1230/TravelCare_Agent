package travelcare_agent.agentops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import travelcare_agent.agent.provider.AgentProviderProperties;
import travelcare_agent.answerability.AnswerabilityDecision;
import travelcare_agent.answerability.AnswerabilityRequest;
import travelcare_agent.answerability.AnswerabilityService;
import travelcare_agent.answerability.BusinessDecisionContext;
import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.common.result.ResultCode;
import travelcare_agent.conversation.entity.Session;
import travelcare_agent.conversation.repository.SessionRepository;
import travelcare_agent.retrieval.service.RetrievalQuery;
import travelcare_agent.retrieval.service.RetrievalService;
import travelcare_agent.retrieval.service.RetrievalSnippet;
import travelcare_agent.trace.RedactionService;
import travelcare_agent.trace.TraceQueryService;
import travelcare_agent.trace.TraceSnapshotType;
import travelcare_agent.trace.entity.TraceRun;
import travelcare_agent.trace.entity.TraceSnapshot;
import travelcare_agent.trace.entity.TraceSpan;
import travelcare_agent.trace.repository.TraceRunRepository;
import travelcare_agent.workflow.entity.Workflow;
import travelcare_agent.workflow.repository.WorkflowRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class AgentOpsDebugService {
    private static final String DEBUG_MODE = "DRY_RUN";
    private final SessionRepository sessions;
    private final WorkflowRepository workflows;
    private final TraceRunRepository traceRuns;
    private final TraceQueryService traceQueryService;
    private final RetrievalService retrievalService;
    private final AnswerabilityService answerabilityService;
    private final AgentProviderProperties providerProperties;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final RedactionService redactionService = new RedactionService();

    public AgentOpsDebugService(SessionRepository sessions, WorkflowRepository workflows,
            TraceRunRepository traceRuns, TraceQueryService traceQueryService, RetrievalService retrievalService,
            AnswerabilityService answerabilityService, AgentProviderProperties providerProperties) {
        this.sessions = sessions;
        this.workflows = workflows;
        this.traceRuns = traceRuns;
        this.traceQueryService = traceQueryService;
        this.retrievalService = retrievalService;
        this.answerabilityService = answerabilityService;
        this.providerProperties = providerProperties;
    }

    public AgentOpsDebugResponse debug(AgentOpsDebugRequest request) {
        validate(request);
        Session session = sessions.findById(request.sessionId())
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "session not found"));
        validateWorkflow(request.sessionId(), request.workflowId());

        Optional<TraceRun> latestTrace = traceRuns.findLatestBySessionIdAndWorkflowId(
                request.sessionId(), request.workflowId());
        if (latestTrace.isPresent()) {
            return fromTrace(request, latestTrace.get());
        }
        return inMemoryDiagnostic(request, session);
    }

    private void validate(AgentOpsDebugRequest request) {
        if (request == null || request.sessionId() == null) {
            throw new BusinessException(ResultCode.VALIDATION_FAILED, "sessionId is required");
        }
        if (request.question() == null || request.question().trim().isEmpty()) {
            throw new BusinessException(ResultCode.VALIDATION_FAILED, "question is required");
        }
        if (Boolean.FALSE.equals(request.dryRun())) {
            throw new BusinessException(ResultCode.AGENTOPS_DRY_RUN_REQUIRED);
        }
    }

    private void validateWorkflow(Long sessionId, Long workflowId) {
        if (workflowId == null) {
            return;
        }
        Workflow workflow = workflows.findById(workflowId)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Workflow not found: " + workflowId));
        if (!sessionId.equals(workflow.getSessionId())) {
            throw new BusinessException(ResultCode.VALIDATION_FAILED, "workflowId does not belong to sessionId");
        }
    }

    private AgentOpsDebugResponse fromTrace(AgentOpsDebugRequest request, TraceRun run) {
        TraceQueryService.TraceDetail detail = traceQueryService.get(run.getTraceId());
        TraceQueryService.TraceDiagnostics diagnostics = traceQueryService.diagnostics(run.getTraceId());
        Map<String, TraceSnapshot> snapshots = latestSnapshots(detail.snapshots());
        List<String> warnings = new ArrayList<>();

        List<AgentOpsDebugResponse.CitationDebug> candidates = citationArray(
                snapshots.get(TraceSnapshotType.RETRIEVAL_SUMMARY.name()), "results", false);
        List<AgentOpsDebugResponse.CitationDebug> accepted = citationArray(
                snapshots.get(TraceSnapshotType.CITATION_SUMMARY.name()), "citations", false);
        List<AgentOpsDebugResponse.CitationDebug> rejected = citationArray(
                snapshots.get(TraceSnapshotType.CITATION_SUMMARY.name()), "rejectedCitationCandidates", true);
        AgentOpsDebugResponse.AnswerabilityDebug answerability = answerability(
                snapshots.get(TraceSnapshotType.ANSWERABILITY_DECISION.name()));
        AgentOpsDebugResponse.SafetyDebug safety = safety(
                snapshots.get(TraceSnapshotType.MODEL_SAFETY_DECISION.name()));
        boolean handoff = workflowNeedsHuman(snapshots.get(TraceSnapshotType.WORKFLOW_PATH.name()));
        DebugFinalRoute route = mapFinalRoute(safety.decision(), answerability.decision(),
                answerabilityRequiredAction(snapshots.get(TraceSnapshotType.ANSWERABILITY_DECISION.name())), handoff);
        if (diagnostics.incomplete()) {
            warnings.add("TRACE_INCOMPLETE");
        }
        return new AgentOpsDebugResponse(
                request.sessionId(), run.getWorkflowId() == null ? request.workflowId() : run.getWorkflowId(),
                run.getTraceId(), DEBUG_MODE, DebugEvidenceMode.TRACE_REPLAY, providerMode(run),
                firstNonBlank(diagnostics.provider(), run.getProvider(), "mock"),
                firstNonBlank(diagnostics.promptVersion(), run.getPromptVersion(), promptVersion()),
                safeText(request.question()),
                new AgentOpsDebugResponse.RetrievalDebug(candidates, accepted, rejected),
                answerability, safety, supplier(false),
                toolCalls(diagnostics.toolCalls()), route, handoff(route), warnings);
    }

    private AgentOpsDebugResponse inMemoryDiagnostic(AgentOpsDebugRequest request, Session session) {
        List<String> warnings = new ArrayList<>();
        warnings.add("NO_EXISTING_TRACE_FOUND_CURRENT_STATE_DIAGNOSTIC_ONLY");
        List<RetrievalSnippet> snippets = retrievalService.retrieve(
                new RetrievalQuery(request.sessionId(), session.getUserId(), request.question(), null, 5));
        AnswerabilityDecision decision = answerabilityService.assess(new AnswerabilityRequest(
                request.question(), snippets, null, null, BusinessDecisionContext.none(), java.time.LocalDateTime.now()));
        List<AgentOpsDebugResponse.CitationDebug> candidates = snippets.stream().map(this::citation).toList();
        List<AgentOpsDebugResponse.CitationDebug> accepted = decision.citations().stream()
                .map(c -> citation(c.retrievalRunId(), c.documentId(), c.chunkId(), c.title(), c.sourceUri(),
                        null, null, string(c.effectiveFrom()), string(c.effectiveTo()), null, null, null))
                .toList();
        List<AgentOpsDebugResponse.CitationDebug> rejected = decision.rejectedCitationCandidates().stream()
                .map(c -> citation(c.retrievalRunId(), c.documentId(), c.chunkId(), c.title(), c.sourceUri(),
                        null, null, string(c.effectiveFrom()), string(c.effectiveTo()), null, null, c.reasonCode().name()))
                .toList();
        String status = decision.status().name();
        String requiredAction = decision.requiredAction().name();
        AgentOpsDebugResponse.AnswerabilityDebug answerability =
                new AgentOpsDebugResponse.AnswerabilityDebug(status, decision.reasonCode().name());
        AgentOpsDebugResponse.SafetyDebug safety =
                new AgentOpsDebugResponse.SafetyDebug("ALLOW", "NO_MODEL_OUTPUT_IN_DRY_RUN", List.of());
        DebugFinalRoute route = mapFinalRoute(safety.decision(), status, requiredAction, false);
        return new AgentOpsDebugResponse(
                request.sessionId(), request.workflowId(), null, DEBUG_MODE, DebugEvidenceMode.CURRENT_DIAGNOSTIC,
                providerProperties == null || providerProperties.getProvider() == null
                        ? "mock" : providerProperties.getProvider().name().toLowerCase(Locale.ROOT),
                "mock", promptVersion(), safeText(request.question()),
                new AgentOpsDebugResponse.RetrievalDebug(candidates, accepted, rejected),
                answerability, safety, supplier(false), List.of(), route, handoff(route), warnings);
    }

    public static DebugFinalRoute mapFinalRoute(String safetyDecision, String answerabilityStatus,
            String requiredAction, boolean handoffRecommended) {
        String safety = normalize(safetyDecision);
        String status = normalize(answerabilityStatus);
        String action = normalize(requiredAction);
        if ("BLOCK".equals(safety)) {
            return DebugFinalRoute.BLOCK;
        }
        if ("CLARIFY".equals(safety)) {
            return DebugFinalRoute.CLARIFY;
        }
        if ("HANDOFF".equals(safety) || handoffRecommended) {
            return DebugFinalRoute.HANDOFF;
        }
        if ("FALLBACK".equals(safety) || "FALLBACK_REPLY".equals(action) || "UNANSWERABLE".equals(status)
                || "NOT_ANSWERABLE".equals(status)) {
            return DebugFinalRoute.FALLBACK;
        }
        return DebugFinalRoute.ALLOW;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private Map<String, TraceSnapshot> latestSnapshots(List<TraceSnapshot> snapshots) {
        Map<String, TraceSnapshot> result = new LinkedHashMap<>();
        for (TraceSnapshot snapshot : snapshots == null ? List.<TraceSnapshot>of() : snapshots) {
            result.put(snapshot.getSnapshotType(), snapshot);
        }
        return result;
    }

    private List<AgentOpsDebugResponse.CitationDebug> citationArray(TraceSnapshot snapshot, String field,
            boolean rejected) {
        if (snapshot == null || snapshot.getPayloadJson() == null) {
            return List.of();
        }
        try {
            JsonNode values = objectMapper.readTree(snapshot.getPayloadJson()).path(field);
            if (!values.isArray()) {
                return List.of();
            }
            List<AgentOpsDebugResponse.CitationDebug> result = new ArrayList<>();
            for (JsonNode value : values) {
                result.add(citation(
                        text(value, "retrievalRunId"),
                        longValue(value, "documentId"),
                        longValue(value, "chunkId"),
                        text(value, "title"),
                        text(value, "sourceUri"),
                        text(value, "sourceAnchor"),
                        text(value, "policyVersion"),
                        text(value, "effectiveFrom"),
                        text(value, "effectiveTo"),
                        text(value, "effectiveTime"),
                        doubleValue(value, "score"),
                        rejected ? text(value, "reasonCode") : null
                ));
            }
            return result;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private AgentOpsDebugResponse.CitationDebug citation(RetrievalSnippet snippet) {
        return citation(snippet.retrievalRunId(), snippet.documentId(), snippet.chunkId(), snippet.title(),
                snippet.sourceUri(), null, null, string(snippet.effectiveFrom()), string(snippet.effectiveTo()),
                null, snippet.score(), null);
    }

    private AgentOpsDebugResponse.CitationDebug citation(String retrievalRunId, Long documentId, Long chunkId,
            String title, String sourceUri, String sourceAnchor, String policyVersion, String effectiveFrom,
            String effectiveTo, String effectiveTime, Double score, String rejectionReason) {
        return new AgentOpsDebugResponse.CitationDebug(
                safeText(retrievalRunId), documentId, documentId, chunkId, safeText(title),
                sanitizeSourceUri(sourceUri), safeText(sourceAnchor), safeText(policyVersion),
                safeText(effectiveFrom), safeText(effectiveTo), safeText(effectiveTime), score,
                safeText(rejectionReason));
    }

    private AgentOpsDebugResponse.AnswerabilityDebug answerability(TraceSnapshot snapshot) {
        if (snapshot == null || snapshot.getPayloadJson() == null) {
            return new AgentOpsDebugResponse.AnswerabilityDebug("NOT_APPLICABLE", "NO_ANSWERABILITY_SNAPSHOT");
        }
        try {
            JsonNode value = objectMapper.readTree(snapshot.getPayloadJson());
            return new AgentOpsDebugResponse.AnswerabilityDebug(
                    firstNonBlank(text(value, "status"), "NOT_APPLICABLE"),
                    firstNonBlank(text(value, "reasonCode"), "NO_REASON"));
        } catch (Exception ex) {
            return new AgentOpsDebugResponse.AnswerabilityDebug("NOT_APPLICABLE", "UNREADABLE_ANSWERABILITY_SNAPSHOT");
        }
    }

    private String answerabilityRequiredAction(TraceSnapshot snapshot) {
        if (snapshot == null || snapshot.getPayloadJson() == null) {
            return null;
        }
        try {
            return text(objectMapper.readTree(snapshot.getPayloadJson()), "requiredAction");
        } catch (Exception ex) {
            return null;
        }
    }

    private AgentOpsDebugResponse.SafetyDebug safety(TraceSnapshot snapshot) {
        if (snapshot == null || snapshot.getPayloadJson() == null) {
            return new AgentOpsDebugResponse.SafetyDebug("ALLOW", "NO_SAFETY_SNAPSHOT", List.of());
        }
        try {
            JsonNode value = objectMapper.readTree(snapshot.getPayloadJson());
            List<String> riskTags = new ArrayList<>();
            JsonNode flags = value.path("riskFlags");
            if (flags.isArray()) {
                for (JsonNode flag : flags) {
                    String code = flag.isTextual() ? flag.asText() : text(flag, "code");
                    if (code != null && !code.isBlank()) {
                        riskTags.add(code);
                    }
                }
            }
            return new AgentOpsDebugResponse.SafetyDebug(
                    firstNonBlank(text(value, "safetyDecision"), text(value, "decision"), "ALLOW"),
                    firstNonBlank(text(value, "reasonCode"), text(value, "reason"), "NO_REASON"),
                    riskTags);
        } catch (Exception ex) {
            return new AgentOpsDebugResponse.SafetyDebug("ALLOW", "UNREADABLE_SAFETY_SNAPSHOT", List.of());
        }
    }

    private boolean workflowNeedsHuman(TraceSnapshot snapshot) {
        if (snapshot == null || snapshot.getPayloadJson() == null) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(snapshot.getPayloadJson());
            if ("NEED_HUMAN".equalsIgnoreCase(text(node, "status"))) {
                return true;
            }
            JsonNode steps = node.path("steps");
            if (steps.isArray()) {
                for (JsonNode step : steps) {
                    if ("NEED_HUMAN".equalsIgnoreCase(text(step, "name"))
                            || "NEED_HUMAN".equalsIgnoreCase(text(step, "status"))) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception ex) {
            return false;
        }
    }

    private List<AgentOpsDebugResponse.ToolCallDebug> toolCalls(List<TraceSpan> spans) {
        if (spans == null) {
            return List.of();
        }
        return spans.stream()
                .map(span -> new AgentOpsDebugResponse.ToolCallDebug(
                        safeText(span.getName()), safeText(span.getStatus()),
                        safeText(span.getErrorCode()), safeText(span.getOutputRef())))
                .toList();
    }

    private AgentOpsDebugResponse.SupplierGatewayDebug supplier(boolean participated) {
        return new AgentOpsDebugResponse.SupplierGatewayDebug(
                participated, participated ? null : "dry-run mode; Supplier Gateway not called");
    }

    private AgentOpsDebugResponse.HumanHandoffRecommendation handoff(DebugFinalRoute route) {
        boolean recommended = route == DebugFinalRoute.HANDOFF;
        return new AgentOpsDebugResponse.HumanHandoffRecommendation(
                recommended, recommended ? "Workflow or safety route requires human review" : "Automated answer allowed");
    }

    private String providerMode(TraceRun run) {
        return firstNonBlank(run.getProvider(),
                providerProperties == null || providerProperties.getProvider() == null
                        ? null : providerProperties.getProvider().name().toLowerCase(Locale.ROOT),
                "mock");
    }

    private String promptVersion() {
        return providerProperties == null ? "stage10a-default" : firstNonBlank(providerProperties.getPromptVersion(), "stage10a-default");
    }

    private String safeText(String value) {
        return value == null ? null : redactionService.redact(value).value();
    }

    private String sanitizeSourceUri(String value) {
        return redactionService.sanitizeSourceUri(value);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static Long longValue(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asLong();
    }

    private static Double doubleValue(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asDouble();
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
