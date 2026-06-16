package travelcare_agent.agent;

import org.springframework.stereotype.Service;
import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.common.result.ResultCode;
import travelcare_agent.answerability.AnswerabilityDecision;
import travelcare_agent.answerability.AnswerabilityRequest;
import travelcare_agent.answerability.AnswerabilityService;
import travelcare_agent.answerability.BusinessDecisionContext;
import travelcare_agent.conversation.entity.Session;
import travelcare_agent.conversation.entity.SessionEvent;
import travelcare_agent.conversation.repository.SessionEventRepository;
import travelcare_agent.conversation.repository.SessionRepository;
import travelcare_agent.enums.MemoryType;
import travelcare_agent.memory.entity.AgentMemory;
import travelcare_agent.memory.service.MemoryService;
import travelcare_agent.refund.entity.RefundCase;
import travelcare_agent.refund.repository.RefundCaseRepository;
import travelcare_agent.retrieval.service.RetrievalQuery;
import travelcare_agent.retrieval.service.RetrievalService;
import travelcare_agent.retrieval.service.RetrievalSnippet;
import travelcare_agent.workflow.entity.Workflow;
import travelcare_agent.workflow.repository.WorkflowRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import travelcare_agent.trace.*;

@Service
public class ContextAssembler {

    private final SessionRepository sessionRepository;
    private final SessionEventRepository sessionEventRepository;
    private final WorkflowRepository workflowRepository;
    private final RefundCaseRepository refundCaseRepository;
    private final RetrievalService retrievalService;
    private final MemoryService memoryService;
    private final TraceService traceService;
    private final AnswerabilityService answerabilityService;

    public ContextAssembler(
            SessionRepository sessionRepository,
            SessionEventRepository sessionEventRepository,
            WorkflowRepository workflowRepository,
            RefundCaseRepository refundCaseRepository,
            RetrievalService retrievalService,
            MemoryService memoryService
    ) {
        this(sessionRepository, sessionEventRepository, workflowRepository, refundCaseRepository,
                retrievalService, memoryService, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ContextAssembler(SessionRepository sessionRepository, SessionEventRepository sessionEventRepository,
            WorkflowRepository workflowRepository, RefundCaseRepository refundCaseRepository,
            RetrievalService retrievalService, MemoryService memoryService, TraceService traceService,
            AnswerabilityService answerabilityService) {
        this.sessionRepository = sessionRepository;
        this.sessionEventRepository = sessionEventRepository;
        this.workflowRepository = workflowRepository;
        this.refundCaseRepository = refundCaseRepository;
        this.retrievalService = retrievalService;
        this.memoryService = memoryService;
        this.traceService = traceService;
        this.answerabilityService = answerabilityService;
    }

    public ContextAssembler(SessionRepository sessionRepository, SessionEventRepository sessionEventRepository,
            WorkflowRepository workflowRepository, RefundCaseRepository refundCaseRepository,
            RetrievalService retrievalService, MemoryService memoryService, TraceService traceService) {
        this(sessionRepository, sessionEventRepository, workflowRepository, refundCaseRepository,
                retrievalService, memoryService, traceService, null);
    }

    public AgentContext assemble(Long sessionId, String query) {
        TraceService.SpanHandle span = traceService == null ? TraceService.SpanHandle.unavailable()
                : traceService.startSpan(SpanType.CONTEXT, "assemble-context", Map.of("sessionId", sessionId));
        try {
            AgentContext result = assembleInternal(sessionId, query);
            if (traceService != null) traceService.recordCurrentSnapshot(TraceSnapshotType.CONTEXT_SUMMARY,
                    "SESSION", String.valueOf(sessionId), Map.of(
                            "sessionId", sessionId,
                            "eventIds", result.recentEvents().stream().map(SessionEvent::getId).toList(),
                            "retrievalChunkIds", result.policySnippets().stream().map(RetrievalSnippet::chunkId).toList(),
                            "memoryIds", result.activeMemories().stream().map(AgentMemory::getId).toList()
                    ));
            if (traceService != null) traceService.finishSpanSuccess(span, null, Map.of(
                    "eventCount", result.recentEvents().size(), "retrievalCount", result.policySnippets().size(),
                    "memoryCount", result.activeMemories().size()));
            return result;
        } catch (RuntimeException ex) {
            if (traceService != null) traceService.finishSpanFailure(span, "CONTEXT_ASSEMBLY_FAILED", ex, Map.of());
            throw ex;
        }
    }

    private AgentContext assembleInternal(Long sessionId, String query) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Session not found: " + sessionId));

        Long userId = session.getUserId();

        List<SessionEvent> events = sessionEventRepository.findBySessionIdOrderBySeqNo(sessionId);
        // Why: Restrict context history to the last 20 events to prevent exceeding model token limits
        // and to keep inference response latency and costs controlled.
        if (events.size() > 20) {
            events = events.subList(events.size() - 20, events.size());
        }

        Workflow activeWorkflow = null;
        RefundCase refundCase = null;
        if (session.getCurrentWorkflowId() != null) {
            activeWorkflow = workflowRepository.findById(session.getCurrentWorkflowId()).orElse(null);
            refundCase = refundCaseRepository.findByWorkflowId(session.getCurrentWorkflowId()).orElse(null);
        }

        List<RetrievalSnippet> snippets = new ArrayList<>();
        if (query != null && !query.trim().isEmpty()) {
            snippets = retrievalService.retrieve(new RetrievalQuery(sessionId, userId, query, null, 5));
        }
        AnswerabilityDecision answerabilityDecision = assessAnswerability(query, activeWorkflow, refundCase, snippets);

        List<AgentMemory> activeMemories = memoryService.getActiveMemories(
                userId,
                List.of(MemoryType.USER_PREFERENCE, MemoryType.TRIP_CONTEXT),
                10
        );

        return new AgentContext(
                events,
                activeWorkflow,
                refundCase,
                snippets,
                activeMemories,
                answerabilityDecision
        );
    }

    private AnswerabilityDecision assessAnswerability(String query, Workflow activeWorkflow, RefundCase refundCase,
            List<RetrievalSnippet> snippets) {
        if (answerabilityService == null) {
            return null;
        }
        BusinessDecisionContext businessContext = refundCase == null
                ? BusinessDecisionContext.none()
                : new BusinessDecisionContext(
                true,
                activeWorkflow == null || activeWorkflow.getStatus() == null ? null : activeWorkflow.getStatus().name(),
                refundCase.getStatus() == null ? null : refundCase.getStatus().name(),
                null,
                refundCase.getRefundAmount() == null ? null : refundCase.getRefundAmount().toPlainString()
        );
        AnswerabilityRequest request = new AnswerabilityRequest(
                query,
                snippets,
                null,
                activeWorkflow == null ? null : activeWorkflow.getWorkflowType(),
                businessContext,
                java.time.LocalDateTime.now()
        );
        TraceService.SpanHandle span = traceService == null ? TraceService.SpanHandle.unavailable()
                : traceService.startSpan(SpanType.ANSWERABILITY, "answerability-gate", Map.of(
                "workflowType", request.workflowType() == null ? "" : request.workflowType(),
                "intent", ""
        ));
        try {
            if (traceService != null) {
                traceService.recordCurrentSnapshot(TraceSnapshotType.ANSWERABILITY_INPUT,
                        "ANSWERABILITY", null, Map.of(
                                "query", query == null ? "" : query,
                                "intent", "",
                                "workflowType", request.workflowType() == null ? "" : request.workflowType(),
                                "businessDecisionContext", businessContext,
                                "retrievalRunIds", snippets.stream().map(RetrievalSnippet::retrievalRunId).distinct().toList(),
                                "chunkIds", snippets.stream().map(RetrievalSnippet::chunkId).toList()
                        ));
            }
            AnswerabilityDecision decision = answerabilityService.assess(request);
            recordAnswerabilityTrace(span, decision);
            return decision;
        } catch (RuntimeException ex) {
            if (traceService != null) traceService.finishSpanFailure(span, "ANSWERABILITY_FAILED", ex, Map.of());
            throw ex;
        }
    }

    private void recordAnswerabilityTrace(TraceService.SpanHandle span, AnswerabilityDecision decision) {
        if (traceService == null || decision == null) {
            return;
        }
        traceService.recordCurrentSnapshot(TraceSnapshotType.ANSWERABILITY_DECISION,
                "ANSWERABILITY", null, Map.of(
                        "status", decision.status().name(),
                        "reasonCode", decision.reasonCode().name(),
                        "requiredAction", decision.requiredAction().name(),
                        "citationPolicy", decision.citationPolicy().name(),
                        "evidenceChunkIds", decision.evidenceChunkIds(),
                        "businessDecisionLocked", decision.businessDecisionLocked(),
                        "ragMayExplainBusinessDecision", decision.ragMayExplainBusinessDecision(),
                        "ragMayOverrideBusinessDecision", decision.ragMayOverrideBusinessDecision()
                ));
        traceService.recordCurrentSnapshot(TraceSnapshotType.CITATION_SUMMARY,
                "ANSWERABILITY", null, Map.of(
                        "citations", decision.citations(),
                        "rejectedCitationCandidates", decision.rejectedCitationCandidates()
                ));
        TraceContextHolder.TraceContext context = TraceContextHolder.current();
        if (context != null) {
            TraceEventType eventType = switch (decision.status()) {
                case ANSWERABLE -> TraceEventType.ANSWERABILITY_PASSED;
                case PARTIAL -> TraceEventType.ANSWERABILITY_PARTIAL;
                case UNANSWERABLE -> TraceEventType.ANSWERABILITY_BLOCKED;
                case NOT_APPLICABLE -> TraceEventType.ANSWERABILITY_PASSED;
            };
            traceService.recordEvent(context.traceId(), context.spanId(), eventType,
                    "answerability-" + decision.status().name().toLowerCase(), Map.of(
                            "reasonCode", decision.reasonCode().name(),
                            "requiredAction", decision.requiredAction().name()
                    ));
            for (var citation : decision.citations()) {
                traceService.recordEvent(context.traceId(), context.spanId(), TraceEventType.CITATION_VALIDATED,
                        "citation-validated", Map.of("chunkId", citation.chunkId()));
            }
            for (var candidate : decision.rejectedCitationCandidates()) {
                traceService.recordEvent(context.traceId(), context.spanId(), TraceEventType.CITATION_REJECTED,
                        "citation-rejected", Map.of("chunkId", candidate.chunkId(), "reasonCode", candidate.reasonCode().name()));
            }
        }
        traceService.finishSpanSuccess(span, null, Map.of(
                "status", decision.status().name(),
                "reasonCode", decision.reasonCode().name()
        ));
    }
}
