package travelcare_agent.agent;

import org.springframework.stereotype.Service;
import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.common.result.ResultCode;
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

    public ContextAssembler(
            SessionRepository sessionRepository,
            SessionEventRepository sessionEventRepository,
            WorkflowRepository workflowRepository,
            RefundCaseRepository refundCaseRepository,
            RetrievalService retrievalService,
            MemoryService memoryService
    ) {
        this(sessionRepository, sessionEventRepository, workflowRepository, refundCaseRepository,
                retrievalService, memoryService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ContextAssembler(SessionRepository sessionRepository, SessionEventRepository sessionEventRepository,
            WorkflowRepository workflowRepository, RefundCaseRepository refundCaseRepository,
            RetrievalService retrievalService, MemoryService memoryService, TraceService traceService) {
        this.sessionRepository = sessionRepository;
        this.sessionEventRepository = sessionEventRepository;
        this.workflowRepository = workflowRepository;
        this.refundCaseRepository = refundCaseRepository;
        this.retrievalService = retrievalService;
        this.memoryService = memoryService;
        this.traceService = traceService;
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
                activeMemories
        );
    }
}
