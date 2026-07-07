package travelcare_agent.human.packet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import travelcare_agent.conversation.entity.SessionEvent;
import travelcare_agent.conversation.repository.SessionEventRepository;
import travelcare_agent.enums.SessionEventRole;
import travelcare_agent.human.entity.HumanReviewCase;
import travelcare_agent.refund.entity.RefundCase;
import travelcare_agent.refund.repository.RefundCaseRepository;
import travelcare_agent.trace.RedactionService;
import travelcare_agent.trace.TraceQueryService;
import travelcare_agent.trace.TraceSnapshotType;
import travelcare_agent.trace.entity.TraceRun;
import travelcare_agent.trace.entity.TraceSnapshot;
import travelcare_agent.trace.entity.TraceSpan;
import travelcare_agent.trace.repository.TraceRunRepository;
import travelcare_agent.workflow.entity.Workflow;
import travelcare_agent.workflow.entity.WorkflowStep;
import travelcare_agent.workflow.repository.WorkflowRepository;
import travelcare_agent.workflow.repository.WorkflowStepRepository;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class HumanHandoffContextPacketBuilder {
    public static final String VERSION = "PR-3A-v1";
    private static final int RECENT_MESSAGE_LIMIT = 6;

    private final SessionEventRepository sessionEvents;
    private final WorkflowRepository workflows;
    private final WorkflowStepRepository workflowSteps;
    private final RefundCaseRepository refundCases;
    private final TraceRunRepository traceRuns;
    private final TraceQueryService traceQueryService;
    private final RedactionService redactionService;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public HumanHandoffContextPacketBuilder(
            SessionEventRepository sessionEvents,
            WorkflowRepository workflows,
            WorkflowStepRepository workflowSteps,
            RefundCaseRepository refundCases,
            TraceRunRepository traceRuns,
            TraceQueryService traceQueryService,
            RedactionService redactionService
    ) {
        this.sessionEvents = sessionEvents;
        this.workflows = workflows;
        this.workflowSteps = workflowSteps;
        this.refundCases = refundCases;
        this.traceRuns = traceRuns;
        this.traceQueryService = traceQueryService;
        this.redactionService = redactionService == null ? new RedactionService() : redactionService;
    }

    public HumanHandoffContextPacket build(Request request) {
        return buildPacket(request, null, "MATERIALIZED", false);
    }

    public HumanHandoffContextPacket fromStoredEvidence(HumanReviewCase hrCase) {
        if (hrCase == null) {
            return minimal(null, null, null, null, "UNKNOWN", List.of("CASE_NOT_FOUND"));
        }
        if (isPacket(hrCase.getEvidenceJson())) {
            try {
                return objectMapper.readValue(hrCase.getEvidenceJson(), HumanHandoffContextPacket.class);
            } catch (Exception ignored) {
                // Fall through to legacy reconstruction.
            }
        }
        Request request = new Request(
                hrCase.getSessionId(),
                hrCase.getWorkflowId(),
                hrCase.getRefundCaseId(),
                hrCase.getCaseType(),
                hrCase.getPriority(),
                hrCase.getReasonCode(),
                hrCase.getEvidenceJson()
        );
        return buildPacket(request, hrCase.getId(), "LEGACY_FALLBACK", true);
    }

    public String buildJson(Request request) {
        try {
            return objectMapper.writeValueAsString(build(request));
        } catch (Exception ex) {
            try {
                return objectMapper.writeValueAsString(minimal(
                        null, request.sessionId(), request.workflowId(), request.refundCaseId(),
                        request.reasonCode(), List.of("PACKET_BUILD_PARTIAL")));
            } catch (Exception ignored) {
                return "{}";
            }
        }
    }

    private HumanHandoffContextPacket buildPacket(Request request, Long caseId, String mode, boolean legacy) {
        List<String> warnings = new ArrayList<>();
        if (legacy) {
            warnings.add("LEGACY_EVIDENCE_JSON");
        }
        Workflow workflow = workflows.findById(request.workflowId()).orElse(null);
        List<WorkflowStep> steps = request.workflowId() == null ? List.of() : workflowSteps.findByWorkflowId(request.workflowId());
        RefundCase refundCase = findRefundCase(request);
        TraceContext trace = traceContext(request.sessionId(), request.workflowId(), warnings);
        HumanHandoffContextPacket.VerifiedOrderFacts orderFacts = orderFacts(steps);
        String reasonCode = firstNonBlank(request.reasonCode(), workflowReason(workflow), refundCase == null ? null : refundCase.getReason(), "NEED_HUMAN");
        List<HumanHandoffContextPacket.ToolCallSummary> toolCalls = toolCalls(trace.diagnostics());
        HumanHandoffContextPacket.SupplierGatewaySummary supplier = supplierGateway(reasonCode, toolCalls);

        return new HumanHandoffContextPacket(
                VERSION,
                mode,
                caseId,
                request.sessionId(),
                request.workflowId(),
                request.refundCaseId(),
                customerGoal(request.sessionId(), orderFacts.orderNo(), reasonCode),
                orderFacts,
                refundDecision(refundCase),
                ragEvidence(trace.detail()),
                toolCalls,
                supplier,
                safetyDecision(trace.detail()),
                new HumanHandoffContextPacket.HandoffReason(reasonCode, explanation(reasonCode)),
                recommendedNextSteps(request.priority(), reasonCode, supplier, safetyDecision(trace.detail()),
                        ragEvidence(trace.detail())),
                warnings
        );
    }

    private RefundCase findRefundCase(Request request) {
        if (request.refundCaseId() != null) {
            Optional<RefundCase> byId = refundCases.findById(request.refundCaseId());
            if (byId.isPresent()) {
                return byId.get();
            }
        }
        if (request.workflowId() != null) {
            return refundCases.findByWorkflowId(request.workflowId()).orElse(null);
        }
        return null;
    }

    private HumanHandoffContextPacket.CustomerGoal customerGoal(Long sessionId, String orderNo, String reasonCode) {
        List<SessionEvent> values = sessionId == null ? List.of() : sessionEvents.findBySessionIdOrderBySeqNo(sessionId);
        List<SessionEvent> recent = values.size() <= RECENT_MESSAGE_LIMIT
                ? values
                : values.subList(values.size() - RECENT_MESSAGE_LIMIT, values.size());
        String latestUser = values.stream()
                .filter(event -> event.getRole() == SessionEventRole.USER)
                .reduce((first, second) -> second)
                .map(SessionEvent::getContent)
                .map(this::safeText)
                .orElse(null);
        String effectiveOrderNo = firstNonBlank(orderNo, extractOrderNo(latestUser));
        return new HumanHandoffContextPacket.CustomerGoal(
                goalSummary(effectiveOrderNo, reasonCode),
                "REFUND_INQUIRY",
                effectiveOrderNo,
                null,
                latestUser,
                recent.stream()
                        .map(event -> new HumanHandoffContextPacket.MessageSummary(
                                event.getRole() == null ? null : event.getRole().name(),
                                safeText(event.getContent()),
                                string(event.getCreatedAt())))
                        .toList()
        );
    }

    private HumanHandoffContextPacket.VerifiedOrderFacts orderFacts(List<WorkflowStep> steps) {
        for (WorkflowStep step : steps) {
            JsonNode node = json(step.getOutputJson());
            String orderNo = text(node, "orderNo");
            if (orderNo != null) {
                return new HumanHandoffContextPacket.VerifiedOrderFacts(
                        longValue(node, "orderId"), orderNo, text(node, "status"),
                        node == null || node.path("refundable").isMissingNode() ? null : node.path("refundable").asBoolean());
            }
        }
        return new HumanHandoffContextPacket.VerifiedOrderFacts(null, null, null, null);
    }

    private HumanHandoffContextPacket.RefundRuleDecision refundDecision(RefundCase refundCase) {
        if (refundCase == null) {
            return new HumanHandoffContextPacket.RefundRuleDecision(null, null, null, null, null);
        }
        return new HumanHandoffContextPacket.RefundRuleDecision(
                refundCase.getId(),
                refundCase.getStatus() == null ? null : refundCase.getStatus().name(),
                refundCase.getRefundAmount(),
                refundCase.getReason(),
                safeText(refundCase.getPolicyResultJson()));
    }

    private HumanHandoffContextPacket.RagEvidence ragEvidence(TraceQueryService.TraceDetail detail) {
        Map<String, TraceSnapshot> snapshots = snapshots(detail);
        TraceSnapshot citation = snapshots.get(TraceSnapshotType.CITATION_SUMMARY.name());
        return new HumanHandoffContextPacket.RagEvidence(
                citationArray(citation, "citations", false),
                citationArray(citation, "rejectedCitationCandidates", true));
    }

    private HumanHandoffContextPacket.SafetyDecisionSummary safetyDecision(TraceQueryService.TraceDetail detail) {
        TraceSnapshot snapshot = snapshots(detail).get(TraceSnapshotType.MODEL_SAFETY_DECISION.name());
        if (snapshot == null || snapshot.getPayloadJson() == null) {
            return new HumanHandoffContextPacket.SafetyDecisionSummary("NOT_APPLICABLE", "NO_SAFETY_SNAPSHOT", List.of());
        }
        JsonNode node = json(snapshot.getPayloadJson());
        List<String> riskTags = new ArrayList<>();
        JsonNode flags = node.path("riskFlags");
        if (flags.isArray()) {
            for (JsonNode flag : flags) {
                riskTags.add(flag.isTextual() ? flag.asText() : text(flag, "code"));
            }
        }
        return new HumanHandoffContextPacket.SafetyDecisionSummary(
                firstNonBlank(text(node, "safetyDecision"), text(node, "decision"), "NOT_APPLICABLE"),
                firstNonBlank(text(node, "reasonCode"), text(node, "reason"), "NO_REASON"),
                riskTags.stream().filter(value -> value != null && !value.isBlank()).toList());
    }

    private List<HumanHandoffContextPacket.ToolCallSummary> toolCalls(TraceQueryService.TraceDiagnostics diagnostics) {
        if (diagnostics == null || diagnostics.toolCalls() == null) {
            return List.of();
        }
        return diagnostics.toolCalls().stream()
                .map(span -> new HumanHandoffContextPacket.ToolCallSummary(
                        safeText(span.getName()), safeText(span.getStatus()), safeText(span.getErrorCode()),
                        safeText(span.getOutputRef())))
                .toList();
    }

    private HumanHandoffContextPacket.SupplierGatewaySummary supplierGateway(
            String reasonCode, List<HumanHandoffContextPacket.ToolCallSummary> toolCalls) {
        String failure = toolCalls.stream()
                .map(HumanHandoffContextPacket.ToolCallSummary::errorCode)
                .filter(this::isSupplierFailure)
                .findFirst()
                .orElse(isSupplierFailure(reasonCode) ? reasonCode : null);
        boolean participated = !toolCalls.isEmpty() || failure != null;
        return new HumanHandoffContextPacket.SupplierGatewaySummary(participated, failure != null, failure);
    }

    private HumanHandoffContextPacket.RecommendedNextSteps recommendedNextSteps(
            String priority,
            String reasonCode,
            HumanHandoffContextPacket.SupplierGatewaySummary supplier,
            HumanHandoffContextPacket.SafetyDecisionSummary safety,
            HumanHandoffContextPacket.RagEvidence evidence) {
        List<HumanHandoffContextPacket.RecommendedStep> steps = new ArrayList<>();
        String normalized = normalize(reasonCode);
        if ("ORDER_REFERENCE_MISSING".equals(normalized)) {
            steps.add(step("REQUEST_ORDER_REFERENCE", "Ask the customer for an order number or booking reference.",
                    "The workflow could not check refund rules without an order reference."));
        }
        if (normalized.contains("OWNERSHIP")) {
            steps.add(step("VERIFY_ORDER_OWNERSHIP", "Verify the order belongs to the current user.",
                    "Refund policy could not complete ownership verification."));
        }
        if ("ORDER_NOT_FOUND".equals(normalized)) {
            steps.add(step("VERIFY_ORDER_IN_AUTHORITY", "Verify the account and order in the authoritative order system.",
                    "The automated lookup did not find the order for this account."));
        }
        if ("ORDER_LOOKUP_FAILED".equals(normalized) || supplier.failed()) {
            steps.add(step("CHECK_SUPPLIER_STATUS", "Check Supplier Gateway health or retry the read-only order lookup.",
                    "The order facts are not reliable until supplier lookup succeeds."));
        }
        if ("HANDOFF".equals(normalize(safety.decision())) || "BLOCK".equals(normalize(safety.decision()))) {
            steps.add(step("REVIEW_SAFETY_DECISION", "Review the safety decision before responding to the customer.",
                    "The model output was not allowed to proceed automatically."));
        }
        if (evidence != null && evidence.rejectedCitations() != null && !evidence.rejectedCitations().isEmpty()) {
            steps.add(step("IGNORE_REJECTED_CITATIONS", "Use accepted citations only; rejected citations are not policy support.",
                    "At least one retrieved citation was rejected."));
        }
        if (steps.isEmpty()) {
            steps.add(step("REVIEW_WORKFLOW_AND_TRACE", "Review workflow, trace, and audit records before responding.",
                    "The case requires manual handling."));
        }
        return new HumanHandoffContextPacket.RecommendedNextSteps(
                firstNonBlank(priority, "HIGH"),
                steps,
                List.of(
                        "Do not promise a refund until order ownership and policy checks are verified.",
                        "Do not retry side-effecting supplier operations without reconciliation."
                ));
    }

    private TraceContext traceContext(Long sessionId, Long workflowId, List<String> warnings) {
        try {
            Optional<TraceRun> run = traceRuns.findLatestBySessionIdAndWorkflowId(sessionId, workflowId);
            if (run.isEmpty()) {
                return new TraceContext(null, null);
            }
            TraceQueryService.TraceDetail detail = traceQueryService.get(run.get().getTraceId());
            TraceQueryService.TraceDiagnostics diagnostics = traceQueryService.diagnostics(run.get().getTraceId());
            return new TraceContext(detail, diagnostics);
        } catch (RuntimeException ex) {
            warnings.add("TRACE_CONTEXT_UNAVAILABLE");
            return new TraceContext(null, null);
        }
    }

    private HumanHandoffContextPacket minimal(Long caseId, Long sessionId, Long workflowId, Long refundCaseId,
            String reasonCode, List<String> warnings) {
        HumanHandoffContextPacket.RagEvidence emptyEvidence = new HumanHandoffContextPacket.RagEvidence(List.of(), List.of());
        HumanHandoffContextPacket.SafetyDecisionSummary safety =
                new HumanHandoffContextPacket.SafetyDecisionSummary("NOT_APPLICABLE", "NO_SAFETY_SNAPSHOT", List.of());
        HumanHandoffContextPacket.SupplierGatewaySummary supplier =
                new HumanHandoffContextPacket.SupplierGatewaySummary(false, false, null);
        return new HumanHandoffContextPacket(
                VERSION, "PARTIAL", caseId, sessionId, workflowId, refundCaseId,
                new HumanHandoffContextPacket.CustomerGoal("Customer needs manual support.", null, null, null, null, List.of()),
                new HumanHandoffContextPacket.VerifiedOrderFacts(null, null, null, null),
                new HumanHandoffContextPacket.RefundRuleDecision(refundCaseId, null, null, null, null),
                emptyEvidence, List.of(), supplier, safety,
                new HumanHandoffContextPacket.HandoffReason(firstNonBlank(reasonCode, "NEED_HUMAN"),
                        explanation(reasonCode)),
                recommendedNextSteps("HIGH", reasonCode, supplier, safety, emptyEvidence),
                warnings);
    }

    private Map<String, TraceSnapshot> snapshots(TraceQueryService.TraceDetail detail) {
        Map<String, TraceSnapshot> result = new LinkedHashMap<>();
        if (detail == null || detail.snapshots() == null) {
            return result;
        }
        for (TraceSnapshot snapshot : detail.snapshots()) {
            result.put(snapshot.getSnapshotType(), snapshot);
        }
        return result;
    }

    private List<HumanHandoffContextPacket.CitationSummary> citationArray(TraceSnapshot snapshot, String field,
            boolean rejected) {
        if (snapshot == null || snapshot.getPayloadJson() == null) {
            return List.of();
        }
        JsonNode values = json(snapshot.getPayloadJson()).path(field);
        if (!values.isArray()) {
            return List.of();
        }
        List<HumanHandoffContextPacket.CitationSummary> result = new ArrayList<>();
        for (JsonNode value : values) {
            result.add(new HumanHandoffContextPacket.CitationSummary(
                    safeText(text(value, "retrievalRunId")),
                    longValue(value, "documentId"),
                    longValue(value, "chunkId"),
                    safeText(text(value, "title")),
                    sanitizeSourceUri(text(value, "sourceUri")),
                    rejected ? safeText(text(value, "reasonCode")) : null));
        }
        return result;
    }

    private HumanHandoffContextPacket.RecommendedStep step(String action, String label, String reason) {
        return new HumanHandoffContextPacket.RecommendedStep(action, label, reason);
    }

    private String goalSummary(String orderNo, String reasonCode) {
        if (orderNo != null && !orderNo.isBlank()) {
            return "Customer wants to check whether order " + orderNo + " can be refunded.";
        }
        if ("ORDER_REFERENCE_MISSING".equals(normalize(reasonCode))) {
            return "Customer wants refund help but did not provide a usable order reference.";
        }
        if ("ORDER_LOOKUP_FAILED".equals(normalize(reasonCode))) {
            return "Customer wants refund help; the system could not verify the order due to lookup failure.";
        }
        return "Customer wants refund help and the case requires manual review.";
    }

    private String explanation(String reasonCode) {
        String normalized = normalize(reasonCode);
        if ("ORDER_REFERENCE_MISSING".equals(normalized)) return "Order reference is required before refund rules can be checked.";
        if ("ORDER_NOT_FOUND".equals(normalized)) return "The system could not find the order for this account.";
        if ("ORDER_LOOKUP_FAILED".equals(normalized)) return "The system could not verify order facts through the supplier lookup.";
        if (normalized.contains("OWNERSHIP")) return "Order ownership could not be verified automatically.";
        return firstNonBlank(reasonCode, "Manual review is required.");
    }

    private String workflowReason(Workflow workflow) {
        if (workflow == null) {
            return null;
        }
        return firstNonBlank(text(json(workflow.getStateJson()), "reasonCode"), workflow.getCurrentStep());
    }

    private boolean isPacket(String evidenceJson) {
        JsonNode node = json(evidenceJson);
        return VERSION.equals(text(node, "packetVersion"));
    }

    private JsonNode json(String value) {
        try {
            if (value == null || value.isBlank()) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(value);
        } catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
    }

    private String safeText(String value) {
        return value == null ? null : redactionService.redact(value).value();
    }

    private String sanitizeSourceUri(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        try {
            URI uri = URI.create(value);
            String query = sanitizeQuery(uri.getRawQuery());
            return new URI(uri.getScheme(), uri.getRawAuthority(), uri.getRawPath(), query, null).toString();
        } catch (Exception ex) {
            return safeText(value);
        }
    }

    private String sanitizeQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return null;
        }
        List<String> kept = new ArrayList<>();
        for (String pair : rawQuery.split("&")) {
            String key = pair;
            String value = "";
            int eq = pair.indexOf('=');
            if (eq >= 0) {
                key = pair.substring(0, eq);
                value = pair.substring(eq + 1);
            }
            String normalized = key.toLowerCase(Locale.ROOT);
            if (normalized.contains("token") || normalized.contains("secret") || normalized.contains("key")
                    || normalized.contains("signature") || normalized.contains("authorization")) {
                continue;
            }
            kept.add(key + (eq >= 0 ? "=" + URLEncoder.encode(value, StandardCharsets.UTF_8) : ""));
        }
        return kept.isEmpty() ? null : String.join("&", kept);
    }

    private boolean isSupplierFailure(String value) {
        String normalized = normalize(value);
        return normalized.startsWith("SUPPLIER_")
                || "ORDER_LOOKUP_FAILED".equals(normalized);
    }

    private String extractOrderNo(String value) {
        if (value == null) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?i)ORD-[A-Z0-9]+").matcher(value);
        return matcher.find() ? matcher.group().toUpperCase(Locale.ROOT) : null;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static Long longValue(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        return value.asLong();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
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

    public record Request(
            Long sessionId,
            Long workflowId,
            Long refundCaseId,
            String caseType,
            String priority,
            String reasonCode,
            String evidenceJson
    ) {
    }

    private record TraceContext(TraceQueryService.TraceDetail detail, TraceQueryService.TraceDiagnostics diagnostics) {
    }
}
