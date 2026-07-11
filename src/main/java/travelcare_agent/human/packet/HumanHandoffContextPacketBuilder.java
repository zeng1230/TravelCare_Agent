package travelcare_agent.human.packet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.common.result.ResultCode;
import travelcare_agent.conversation.entity.SessionEvent;
import travelcare_agent.conversation.repository.SessionEventRepository;
import travelcare_agent.conversation.repository.SessionRepository;
import travelcare_agent.enums.SessionEventRole;
import travelcare_agent.enums.WorkflowStepStatus;
import travelcare_agent.evidence.*;
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

import java.util.*;

@Service
public class HumanHandoffContextPacketBuilder {
    public static final String VERSION = "PR-4D-v1";
    private static final String REFUND_WORKFLOW = "order_refund_inquiry";
    private static final int RECENT_MESSAGE_LIMIT = 6;

    private final SessionRepository sessions;
    private final SessionEventRepository sessionEvents;
    private final WorkflowRepository workflows;
    private final WorkflowStepRepository workflowSteps;
    private final RefundCaseRepository refundCases;
    private final TraceRunRepository traceRuns;
    private final TraceQueryService traceQueryService;
    private final RedactionService redactionService;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Autowired
    public HumanHandoffContextPacketBuilder(SessionRepository sessions, SessionEventRepository sessionEvents,
            WorkflowRepository workflows, WorkflowStepRepository workflowSteps, RefundCaseRepository refundCases,
            TraceRunRepository traceRuns, TraceQueryService traceQueryService, RedactionService redactionService) {
        this.sessions = sessions;
        this.sessionEvents = sessionEvents;
        this.workflows = workflows;
        this.workflowSteps = workflowSteps;
        this.refundCases = refundCases;
        this.traceRuns = traceRuns;
        this.traceQueryService = traceQueryService;
        this.redactionService = redactionService == null ? new RedactionService() : redactionService;
    }

    /** Test/source compatibility; production injection always supplies SessionRepository. */
    public HumanHandoffContextPacketBuilder(SessionEventRepository sessionEvents, WorkflowRepository workflows,
            WorkflowStepRepository workflowSteps, RefundCaseRepository refundCases, TraceRunRepository traceRuns,
            TraceQueryService traceQueryService, RedactionService redactionService) {
        this(null, sessionEvents, workflows, workflowSteps, refundCases, traceRuns, traceQueryService, redactionService);
    }

    public HumanHandoffContextPacket build(Request request) {
        return buildOutcome(request).value();
    }

    public BuildOutcome<HumanHandoffContextPacket> buildOutcome(Request request) {
        return buildPacket(request, null, "MATERIALIZED");
    }

    public HumanHandoffContextPacket fromStoredEvidence(HumanReviewCase hrCase) {
        return fromStoredEvidenceOutcome(hrCase).value();
    }

    public BuildOutcome<HumanHandoffContextPacket> fromStoredEvidenceOutcome(HumanReviewCase hrCase) {
        if (hrCase == null) throw new BusinessException(ResultCode.NOT_FOUND, "Human review case not found");
        Request request = new Request(hrCase.getSessionId(), hrCase.getWorkflowId(), hrCase.getRefundCaseId(),
                hrCase.getCaseType(), hrCase.getPriority(), hrCase.getReasonCode(), null);
        return buildPacket(request, hrCase.getId(), "REBUILT_FROM_DURABLE_FACTS");
    }

    public String buildJson(Request request) {
        return toJson(build(request));
    }

    public String toJson(HumanHandoffContextPacket packet) {
        try {
            return objectMapper.writeValueAsString(packet);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "handoff packet serialization failed");
        }
    }

    private BuildOutcome<HumanHandoffContextPacket> buildPacket(Request request, Long caseId, String mode) {
        if (request == null || request.sessionId() == null || request.workflowId() == null) {
            throw new BusinessException(ResultCode.VALIDATION_FAILED, "sessionId and workflowId are required");
        }
        if (sessions != null && sessions.findById(request.sessionId()).isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "session not found");
        }
        Workflow workflow = workflows.findById(request.workflowId())
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Workflow not found: " + request.workflowId()));
        if (!request.sessionId().equals(workflow.getSessionId())) {
            throw new BusinessException(ResultCode.VALIDATION_FAILED, "workflowId does not belong to sessionId");
        }

        List<WorkflowStep> steps = loadSteps(request.workflowId());
        SectionResult<TraceEvidence> trace = loadTrace(request.sessionId(), request.workflowId());
        SectionResult<Boolean> snapshot = traceSnapshot(trace);
        SectionResult<CitationEvidence> citations = citationEvidence(trace, workflow);
        SectionResult<HumanHandoffContextPacket.AnswerabilitySummary> answerability =
                answerabilityEvidence(trace, citations, workflow);
        SectionResult<HumanHandoffContextPacket.SupplierGatewaySummary> supplier =
                supplierEvidence(trace, steps, request.reasonCode());
        RefundSections refund = refundEvidence(request, workflow);

        List<SectionResult<?>> sections = List.of(trace, snapshot, answerability, citations, supplier,
                refund.refundCase(), refund.policyResult());
        CompletenessAssessment completeness = CompletenessAssessment.derive(sections);
        HumanHandoffContextPacket.VerifiedOrderFacts orderFacts = orderFacts(steps);
        String reasonCode = firstNonBlank(request.reasonCode(), workflowReason(workflow),
                refund.refundCaseValue() == null ? null : refund.refundCaseValue().getReason(), "NEED_HUMAN");
        HumanHandoffContextPacket.RagEvidence rag = citations.value().rag();
        HumanHandoffContextPacket.SafetyDecisionSummary safety = safetyDecision(trace.value().detail());
        List<HumanHandoffContextPacket.ToolCallSummary> tools = toolCalls(trace.value().diagnostics());

        HumanHandoffContextPacket packet = new HumanHandoffContextPacket(
                VERSION, mode, caseId, request.sessionId(), request.workflowId(),
                refund.refundCaseValue() == null ? request.refundCaseId() : refund.refundCaseValue().getId(),
                customerGoal(request.sessionId(), orderFacts.orderNo(), reasonCode), orderFacts,
                refund.decision(), rag, tools, supplier.value(), safety,
                new HumanHandoffContextPacket.HandoffReason(reasonCode, explanation(reasonCode)),
                recommendedNextSteps(request.priority(), reasonCode, supplier.value(), safety, rag), List.of(),
                completeness.status().name(), completeness.missingSections(), completeness.riskWarnings(),
                answerability.value());
        return new BuildOutcome<>(packet, completeness, trace.value().traceId());
    }

    private List<WorkflowStep> loadSteps(Long workflowId) {
        try {
            List<WorkflowStep> values = workflowSteps.findByWorkflowId(workflowId);
            return values == null ? List.of() : values;
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private SectionResult<TraceEvidence> loadTrace(Long sessionId, Long workflowId) {
        TraceEvidence empty = new TraceEvidence(null, null, null);
        try {
            Optional<TraceRun> run = traceRuns.findLatestBySessionIdAndWorkflowId(sessionId, workflowId);
            if (run.isEmpty()) return SectionResult.unavailable(empty, EvidenceSection.TRACE,
                    RiskWarning.TRACE_EVIDENCE_UNAVAILABLE);
            TraceQueryService.TraceDetail detail = null;
            TraceQueryService.TraceDiagnostics diagnostics = null;
            try { detail = traceQueryService.get(run.get().getTraceId()); } catch (RuntimeException ignored) { }
            try { diagnostics = traceQueryService.diagnostics(run.get().getTraceId()); } catch (RuntimeException ignored) { }
            return SectionResult.available(new TraceEvidence(run.get().getTraceId(), detail, diagnostics));
        } catch (RuntimeException ex) {
            return SectionResult.unavailable(empty, EvidenceSection.TRACE, RiskWarning.TRACE_EVIDENCE_UNAVAILABLE);
        }
    }

    private SectionResult<Boolean> traceSnapshot(SectionResult<TraceEvidence> trace) {
        if (trace.status() != SectionStatus.AVAILABLE) return SectionResult.notApplicable(false);
        if (trace.value().detail() == null || trace.value().detail().snapshots() == null) {
            return SectionResult.unavailable(false, EvidenceSection.TRACE_SNAPSHOT,
                    RiskWarning.TRACE_SNAPSHOT_CORRUPTED);
        }
        return SectionResult.available(true);
    }

    private SectionResult<CitationEvidence> citationEvidence(SectionResult<TraceEvidence> trace, Workflow workflow) {
        CitationEvidence empty = new CitationEvidence(new HumanHandoffContextPacket.RagEvidence(List.of(), List.of()));
        if (!isRefundWorkflow(workflow)) return SectionResult.notApplicable(empty);
        TraceSnapshot snapshot = latestSnapshot(trace.value().detail(), TraceSnapshotType.CITATION_SUMMARY);
        if (snapshot == null || snapshot.getPayloadJson() == null) {
            return SectionResult.unavailable(empty, EvidenceSection.CITATION,
                    RiskWarning.CITATION_EVIDENCE_UNAVAILABLE);
        }
        try {
            JsonNode root = objectMapper.readTree(snapshot.getPayloadJson());
            if (!root.path("citations").isArray()) throw new IllegalArgumentException("invalid citation payload");
            return SectionResult.available(new CitationEvidence(new HumanHandoffContextPacket.RagEvidence(
                    citationArray(root.path("citations"), false),
                    citationArray(root.path("rejectedCitationCandidates"), true))));
        } catch (Exception ex) {
            return SectionResult.corrupted(empty, EvidenceSection.CITATION,
                    RiskWarning.CITATION_EVIDENCE_UNAVAILABLE);
        }
    }

    private SectionResult<HumanHandoffContextPacket.AnswerabilitySummary> answerabilityEvidence(
            SectionResult<TraceEvidence> trace, SectionResult<CitationEvidence> citation, Workflow workflow) {
        HumanHandoffContextPacket.AnswerabilitySummary unknown =
                new HumanHandoffContextPacket.AnswerabilitySummary("UNKNOWN", "EVIDENCE_UNAVAILABLE");
        if (!isRefundWorkflow(workflow)) return SectionResult.notApplicable(unknown);
        if (citation.status() != SectionStatus.AVAILABLE) {
            return SectionResult.unavailable(unknown, EvidenceSection.ANSWERABILITY,
                    RiskWarning.ANSWERABILITY_UNKNOWN);
        }
        TraceSnapshot snapshot = latestSnapshot(trace.value().detail(), TraceSnapshotType.ANSWERABILITY_DECISION);
        if (snapshot == null || snapshot.getPayloadJson() == null) {
            return SectionResult.unavailable(unknown, EvidenceSection.ANSWERABILITY,
                    RiskWarning.ANSWERABILITY_UNKNOWN);
        }
        try {
            JsonNode root = objectMapper.readTree(snapshot.getPayloadJson());
            String status = text(root, "status");
            if (status == null) throw new IllegalArgumentException("missing answerability status");
            return SectionResult.available(new HumanHandoffContextPacket.AnswerabilitySummary(
                    safeText(status), firstNonBlank(safeText(text(root, "reasonCode")), "NO_REASON")));
        } catch (Exception ex) {
            return SectionResult.corrupted(unknown, EvidenceSection.ANSWERABILITY,
                    RiskWarning.ANSWERABILITY_UNKNOWN);
        }
    }

    private SectionResult<HumanHandoffContextPacket.SupplierGatewaySummary> supplierEvidence(
            SectionResult<TraceEvidence> trace, List<WorkflowStep> steps, String reasonCode) {
        boolean expected = steps.stream().anyMatch(s -> "QUERYING_ORDER".equals(s.getStepName()))
                || isSupplierFailure(reasonCode);
        List<TraceSpan> spans = trace.value().diagnostics() == null || trace.value().diagnostics().toolCalls() == null
                ? List.of() : trace.value().diagnostics().toolCalls();
        Optional<TraceSpan> actual = spans.stream().filter(s -> "GetOrderTool".equals(s.getName())
                || isSupplierFailure(s.getErrorCode())).findFirst();
        if (!expected && actual.isEmpty()) {
            return SectionResult.notApplicable(new HumanHandoffContextPacket.SupplierGatewaySummary(
                    false, false, null, "NOT_APPLICABLE", false));
        }
        if (actual.isEmpty()) {
            return SectionResult.unavailable(new HumanHandoffContextPacket.SupplierGatewaySummary(
                    true, false, null, "UNAVAILABLE", false), EvidenceSection.SUPPLIER_DIAGNOSTIC,
                    RiskWarning.SUPPLIER_EVIDENCE_UNAVAILABLE);
        }
        String failure = safeText(actual.get().getErrorCode());
        return SectionResult.available(new HumanHandoffContextPacket.SupplierGatewaySummary(
                true, failure != null, failure, failure == null ? "AVAILABLE" : "FAILED", true));
    }

    private RefundSections refundEvidence(Request request, Workflow workflow) {
        HumanHandoffContextPacket.RefundRuleDecision unknown = unknownRefund(request.refundCaseId());
        if (!isRefundWorkflow(workflow)) {
            return new RefundSections(SectionResult.notApplicable(null), SectionResult.notApplicable(null), null, unknown);
        }
        RefundCase refund;
        try {
            refund = request.refundCaseId() == null ? null : refundCases.findById(request.refundCaseId()).orElse(null);
            if (refund == null) refund = refundCases.findByWorkflowId(request.workflowId()).orElse(null);
        } catch (RuntimeException ex) {
            return missingRefund(request.refundCaseId());
        }
        if (refund == null || !request.workflowId().equals(refund.getWorkflowId())) return missingRefund(request.refundCaseId());
        SectionResult<RefundCase> refundSection = SectionResult.available(refund);
        if (refund.getPolicyResultJson() == null || refund.getPolicyResultJson().isBlank()) {
            return new RefundSections(refundSection,
                    SectionResult.unavailable(null, EvidenceSection.REFUND_POLICY_RESULT,
                            RiskWarning.REFUND_DECISION_UNVERIFIED,
                            RiskWarning.MANUAL_REFUND_REQUIRES_VERIFICATION), refund, unknownRefund(refund.getId()));
        }
        try {
            JsonNode policy = objectMapper.readTree(refund.getPolicyResultJson());
            if (policy == null || !policy.isObject()
                    || firstNonBlank(text(policy, "decision"), text(policy, "status")) == null) {
                throw new IllegalArgumentException("invalid policy result");
            }
        } catch (Exception ex) {
            return new RefundSections(refundSection,
                    SectionResult.corrupted(null, EvidenceSection.REFUND_POLICY_RESULT,
                            RiskWarning.REFUND_DECISION_UNVERIFIED,
                            RiskWarning.MANUAL_REFUND_REQUIRES_VERIFICATION), refund, unknownRefund(refund.getId()));
        }
        HumanHandoffContextPacket.RefundRuleDecision decision = new HumanHandoffContextPacket.RefundRuleDecision(
                refund.getId(), refund.getStatus() == null ? "UNKNOWN" : refund.getStatus().name(),
                refund.getRefundAmount(), safeText(refund.getReason()), safeText(refund.getPolicyResultJson()), true, true);
        return new RefundSections(refundSection, SectionResult.available(refund.getPolicyResultJson()), refund, decision);
    }

    private RefundSections missingRefund(Long refundCaseId) {
        return new RefundSections(
                SectionResult.unavailable(null, EvidenceSection.REFUND_CASE,
                        RiskWarning.REFUND_DECISION_UNVERIFIED,
                        RiskWarning.MANUAL_REFUND_REQUIRES_VERIFICATION),
                SectionResult.unavailable(null, EvidenceSection.REFUND_POLICY_RESULT,
                        RiskWarning.REFUND_DECISION_UNVERIFIED,
                        RiskWarning.MANUAL_REFUND_REQUIRES_VERIFICATION), null, unknownRefund(refundCaseId));
    }

    private HumanHandoffContextPacket.RefundRuleDecision unknownRefund(Long id) {
        return new HumanHandoffContextPacket.RefundRuleDecision(id, "UNKNOWN", null, null, null, false, false);
    }

    private HumanHandoffContextPacket.VerifiedOrderFacts orderFacts(List<WorkflowStep> steps) {
        for (WorkflowStep step : steps) {
            if (!"QUERYING_ORDER".equals(step.getStepName()) || step.getStatus() != WorkflowStepStatus.SUCCESS) continue;
            try {
                JsonNode node = objectMapper.readTree(step.getOutputJson());
                Long orderId = longValue(node, "orderId");
                String orderNo = text(node, "orderNo");
                if (orderId == null || orderNo == null || orderNo.isBlank()) continue;
                return new HumanHandoffContextPacket.VerifiedOrderFacts(orderId, safeText(orderNo),
                        safeText(text(node, "status")), node.path("refundable").isMissingNode()
                        ? null : node.path("refundable").asBoolean(), true, "WORKFLOW_STEP_OUTPUT");
            } catch (Exception ignored) { }
        }
        return new HumanHandoffContextPacket.VerifiedOrderFacts(null, null, null, null, false, null);
    }

    private HumanHandoffContextPacket.CustomerGoal customerGoal(Long sessionId, String verifiedOrderNo,
            String reasonCode) {
        List<SessionEvent> values;
        try { values = sessionEvents.findBySessionIdOrderBySeqNo(sessionId); }
        catch (RuntimeException ex) { values = List.of(); }
        List<SessionEvent> recent = values.size() <= RECENT_MESSAGE_LIMIT ? values
                : values.subList(values.size() - RECENT_MESSAGE_LIMIT, values.size());
        String latestUser = values.stream().filter(event -> event.getRole() == SessionEventRole.USER)
                .reduce((first, second) -> second).map(SessionEvent::getContent).map(this::safeText).orElse(null);
        String unverifiedOrderNo = firstNonBlank(verifiedOrderNo, extractOrderNo(latestUser));
        return new HumanHandoffContextPacket.CustomerGoal(goalSummary(unverifiedOrderNo, reasonCode),
                "REFUND_INQUIRY", unverifiedOrderNo, null, latestUser,
                recent.stream().map(event -> new HumanHandoffContextPacket.MessageSummary(
                        event.getRole() == null ? null : event.getRole().name(), safeText(event.getContent()),
                        string(event.getCreatedAt()))).toList(), "UNVERIFIED_CONVERSATION_CONTEXT");
    }

    private HumanHandoffContextPacket.SafetyDecisionSummary safetyDecision(TraceQueryService.TraceDetail detail) {
        TraceSnapshot snapshot = latestSnapshot(detail, TraceSnapshotType.MODEL_SAFETY_DECISION);
        if (snapshot == null || snapshot.getPayloadJson() == null) {
            return new HumanHandoffContextPacket.SafetyDecisionSummary("UNKNOWN", "EVIDENCE_UNAVAILABLE", List.of());
        }
        try {
            JsonNode node = objectMapper.readTree(snapshot.getPayloadJson());
            List<String> risks = new ArrayList<>();
            if (node.path("riskFlags").isArray()) node.path("riskFlags").forEach(flag -> {
                String code = flag.isTextual() ? flag.asText() : text(flag, "code");
                if (code != null) risks.add(safeText(code));
            });
            return new HumanHandoffContextPacket.SafetyDecisionSummary(
                    firstNonBlank(safeText(text(node, "safetyDecision")), safeText(text(node, "decision")), "UNKNOWN"),
                    firstNonBlank(safeText(text(node, "reasonCode")), "NO_REASON"), risks);
        } catch (Exception ex) {
            return new HumanHandoffContextPacket.SafetyDecisionSummary("UNKNOWN", "EVIDENCE_UNAVAILABLE", List.of());
        }
    }

    private List<HumanHandoffContextPacket.ToolCallSummary> toolCalls(TraceQueryService.TraceDiagnostics diagnostics) {
        if (diagnostics == null || diagnostics.toolCalls() == null) return List.of();
        return diagnostics.toolCalls().stream().map(span -> new HumanHandoffContextPacket.ToolCallSummary(
                safeText(span.getName()), safeText(span.getStatus()), safeText(span.getErrorCode()),
                safeText(span.getOutputRef()))).toList();
    }

    private TraceSnapshot latestSnapshot(TraceQueryService.TraceDetail detail, TraceSnapshotType type) {
        if (detail == null || detail.snapshots() == null) return null;
        return detail.snapshots().stream().filter(s -> type.name().equals(s.getSnapshotType()))
                .reduce((first, second) -> second).orElse(null);
    }

    private List<HumanHandoffContextPacket.CitationSummary> citationArray(JsonNode values, boolean rejected) {
        if (values == null || !values.isArray()) return List.of();
        List<HumanHandoffContextPacket.CitationSummary> result = new ArrayList<>();
        for (JsonNode value : values) result.add(new HumanHandoffContextPacket.CitationSummary(
                safeText(text(value, "retrievalRunId")), longValue(value, "documentId"), longValue(value, "chunkId"),
                safeText(text(value, "title")), redactionService.sanitizeSourceUri(text(value, "sourceUri")),
                rejected ? safeText(text(value, "reasonCode")) : null));
        return List.copyOf(result);
    }

    private HumanHandoffContextPacket.RecommendedNextSteps recommendedNextSteps(String priority, String reasonCode,
            HumanHandoffContextPacket.SupplierGatewaySummary supplier,
            HumanHandoffContextPacket.SafetyDecisionSummary safety, HumanHandoffContextPacket.RagEvidence evidence) {
        List<HumanHandoffContextPacket.RecommendedStep> steps = new ArrayList<>();
        String normalized = normalize(reasonCode);
        if ("ORDER_REFERENCE_MISSING".equals(normalized)) steps.add(step("REQUEST_ORDER_REFERENCE",
                "Ask the customer for an order number or booking reference.",
                "The workflow could not check refund rules without an order reference."));
        if (normalized.contains("OWNERSHIP")) steps.add(step("VERIFY_ORDER_OWNERSHIP",
                "Verify the order belongs to the current user.",
                "Refund policy could not complete ownership verification."));
        if ("ORDER_NOT_FOUND".equals(normalized)) steps.add(step("VERIFY_ORDER_IN_AUTHORITY",
                "Verify the account and order in the authoritative order system.",
                "The automated lookup did not find the order for this account."));
        if ("ORDER_LOOKUP_FAILED".equals(normalized) || supplier.failed()) steps.add(step("CHECK_SUPPLIER_STATUS",
                "Check Supplier Gateway health or retry the read-only order lookup.",
                "The order facts are not reliable until supplier lookup succeeds."));
        if ("HANDOFF".equals(normalize(safety.decision())) || "BLOCK".equals(normalize(safety.decision())))
            steps.add(step("REVIEW_SAFETY_DECISION", "Review the safety decision before responding to the customer.",
                    "The model output was not allowed to proceed automatically."));
        if (!evidence.rejectedCitations().isEmpty()) steps.add(step("IGNORE_REJECTED_CITATIONS",
                "Use accepted citations only; rejected citations are not policy support.",
                "At least one retrieved citation was rejected."));
        if (steps.isEmpty()) steps.add(step("REVIEW_WORKFLOW_AND_TRACE",
                "Review workflow, trace, and audit records before responding.", "The case requires manual handling."));
        return new HumanHandoffContextPacket.RecommendedNextSteps(firstNonBlank(priority, "HIGH"), steps,
                List.of("Do not promise a refund until order ownership and policy checks are verified.",
                        "Do not retry side-effecting supplier operations without reconciliation."));
    }

    private HumanHandoffContextPacket.RecommendedStep step(String action, String label, String reason) {
        return new HumanHandoffContextPacket.RecommendedStep(action, label, reason);
    }

    private boolean isRefundWorkflow(Workflow workflow) {
        return workflow != null && REFUND_WORKFLOW.equals(workflow.getWorkflowType());
    }

    private String workflowReason(Workflow workflow) {
        if (workflow == null) return null;
        try { return firstNonBlank(text(objectMapper.readTree(workflow.getStateJson()), "reasonCode"), workflow.getCurrentStep()); }
        catch (Exception ex) { return workflow.getCurrentStep(); }
    }

    private String goalSummary(String orderNo, String reasonCode) {
        if (orderNo != null && !orderNo.isBlank()) return "Customer wants to check whether order " + orderNo + " can be refunded.";
        if ("ORDER_REFERENCE_MISSING".equals(normalize(reasonCode))) return "Customer wants refund help but did not provide a usable order reference.";
        if ("ORDER_LOOKUP_FAILED".equals(normalize(reasonCode))) return "Customer wants refund help; the system could not verify the order due to lookup failure.";
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

    private boolean isSupplierFailure(String value) {
        String normalized = normalize(value);
        return normalized.startsWith("SUPPLIER_") || "ORDER_LOOKUP_FAILED".equals(normalized);
    }

    private String extractOrderNo(String value) {
        if (value == null) return null;
        var matcher = java.util.regex.Pattern.compile("(?i)ORD-[A-Z0-9]+").matcher(value);
        return matcher.find() ? matcher.group().toUpperCase(Locale.ROOT) : null;
    }

    private String safeText(String value) { return value == null ? null : redactionService.redact(value).value(); }
    private static String normalize(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private static String string(Object value) { return value == null ? null : String.valueOf(value); }
    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
    private static Long longValue(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || value.asText().isBlank()) return null;
        try { return value.asLong(); } catch (RuntimeException ex) { return null; }
    }
    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    public record Request(Long sessionId, Long workflowId, Long refundCaseId, String caseType, String priority,
                          String reasonCode, String evidenceJson) { }
    private record TraceEvidence(String traceId, TraceQueryService.TraceDetail detail,
                                 TraceQueryService.TraceDiagnostics diagnostics) { }
    private record CitationEvidence(HumanHandoffContextPacket.RagEvidence rag) { }
    private record RefundSections(SectionResult<RefundCase> refundCase, SectionResult<String> policyResult,
                                  RefundCase refundCaseValue,
                                  HumanHandoffContextPacket.RefundRuleDecision decision) { }
}
