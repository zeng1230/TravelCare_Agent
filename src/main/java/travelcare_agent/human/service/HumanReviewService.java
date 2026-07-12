package travelcare_agent.human.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import travelcare_agent.audit.AuditService;
import travelcare_agent.conversation.service.SessionEventService;
import travelcare_agent.enums.HumanReviewCaseStatus;
import travelcare_agent.enums.HumanReviewResolution;
import travelcare_agent.enums.RefundCaseStatus;
import travelcare_agent.enums.SessionEventRole;
import travelcare_agent.enums.WorkflowStatus;
import travelcare_agent.human.entity.HumanReviewCase;
import travelcare_agent.human.packet.HumanHandoffContextPacket;
import travelcare_agent.human.packet.HumanHandoffContextPacketBuilder;
import travelcare_agent.human.repository.HumanReviewCaseRepository;
import travelcare_agent.refund.entity.RefundCase;
import travelcare_agent.refund.repository.RefundCaseRepository;
import travelcare_agent.workflow.entity.Workflow;
import travelcare_agent.workflow.repository.WorkflowRepository;
import travelcare_agent.conversation.entity.Session;
import travelcare_agent.conversation.repository.SessionRepository;
import travelcare_agent.security.AuthorizationService;
import travelcare_agent.security.CurrentUser;

import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.common.result.ResultCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Map;

import travelcare_agent.trace.*;
import travelcare_agent.evidence.BuildOutcome;
import travelcare_agent.evidence.DegradationRecorder;

@Service
public class HumanReviewService {

    private final HumanReviewCaseRepository repository;
    private final SessionEventService eventService;
    private final AuditService auditService;
    private final WorkflowRepository workflowRepository;
    private final RefundCaseRepository refundCaseRepository;
    private final TraceService traceService;
    private final HumanHandoffContextPacketBuilder contextPacketBuilder;
    private final SessionRepository sessionRepository;
    private final AuthorizationService authorizationService;
    private DegradationRecorder degradationRecorder;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setDegradationRecorder(DegradationRecorder degradationRecorder) {
        this.degradationRecorder = degradationRecorder;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public HumanReviewService(
            HumanReviewCaseRepository repository,
            SessionEventService eventService,
            AuditService auditService,
            WorkflowRepository workflowRepository,
            RefundCaseRepository refundCaseRepository,
            TraceService traceService,
            HumanHandoffContextPacketBuilder contextPacketBuilder,
            SessionRepository sessionRepository,
            AuthorizationService authorizationService
    ) {
        this.repository = repository;
        this.eventService = eventService;
        this.auditService = auditService;
        this.workflowRepository = workflowRepository;
        this.refundCaseRepository = refundCaseRepository;
        this.traceService = traceService;
        this.contextPacketBuilder = contextPacketBuilder;
        this.sessionRepository = sessionRepository;
        this.authorizationService = authorizationService;
    }

    public HumanReviewService(
            HumanReviewCaseRepository repository,
            SessionEventService eventService,
            AuditService auditService,
            WorkflowRepository workflowRepository,
            RefundCaseRepository refundCaseRepository,
            TraceService traceService
    ) {
        this(repository, eventService, auditService, workflowRepository, refundCaseRepository, traceService, null, null, null);
    }

    public HumanReviewService(
            HumanReviewCaseRepository repository,
            SessionEventService eventService,
            AuditService auditService,
            WorkflowRepository workflowRepository,
            RefundCaseRepository refundCaseRepository,
            HumanHandoffContextPacketBuilder contextPacketBuilder
    ) {
        this(repository, eventService, auditService, workflowRepository, refundCaseRepository, null, contextPacketBuilder, null, null);
    }

    public HumanReviewService(HumanReviewCaseRepository repository, SessionEventService eventService,
                              AuditService auditService, WorkflowRepository workflowRepository, RefundCaseRepository refundCaseRepository) {
        this(repository, eventService, auditService, workflowRepository, refundCaseRepository, null, null, null, null);
    }

    @Transactional
    public HumanReviewCase createCase(
            Long sessionId,
            Long workflowId,
            Long refundCaseId,
            String caseType,
            String priority,
            String reasonCode,
            String evidenceJson
    ) {
        travelcare_agent.dryrun.SideEffectGuard.checkCurrent(travelcare_agent.dryrun.SideEffectOperation.HUMAN_REVIEW_WRITE);
        ResourceGraph graph = validateResourceGraph(sessionId, workflowId, refundCaseId, null);
        TraceService.SpanHandle span = traceService == null ? TraceService.SpanHandle.unavailable()
                : traceService.startSpan(SpanType.HUMAN_REVIEW, "create-human-review", Map.of("reasonCode", reasonCode));
        if (traceService != null)
            traceService.recordEvent(span.traceId(), span.spanId(), TraceEventType.HANDOFF_REQUIRED,
                    "handoff-required", Map.of("reasonCode", reasonCode));
        HumanReviewCase hrCase = new HumanReviewCase();
        hrCase.setTenantId(graph.session().getTenantId());
        hrCase.setSessionId(sessionId);
        hrCase.setWorkflowId(workflowId);
        hrCase.setRefundCaseId(refundCaseId);
        hrCase.setCaseType(caseType);
        hrCase.setStatus(HumanReviewCaseStatus.OPEN);
        hrCase.setVersion(0L);
        hrCase.setPriority(priority);
        hrCase.setReasonCode(reasonCode);
        hrCase.setEvidenceJson(contextPacketJson(sessionId, workflowId, refundCaseId, caseType, priority, reasonCode, evidenceJson));
        hrCase.setCreatedAt(LocalDateTime.now());
        hrCase.setUpdatedAt(LocalDateTime.now());

        hrCase = repository.insert(hrCase);

        auditService.recordSystem(
                graph.session().getTenantId(),
                sessionId,
                workflowId,
                "CREATE",
                "HUMAN_REVIEW_CASE",
                hrCase.getId(),
                "{\"status\":\"OPEN\"}",
                "{\"reasonCode\":\"" + reasonCode + "\"}"
        );

        if (traceService != null)
            traceService.finishSpanSuccess(span, "HUMAN_REVIEW_CASE:" + hrCase.getId(), Map.of("status", hrCase.getStatus().name()));
        return hrCase;
    }

    @Transactional
    public HumanReviewCase assignCase(Long caseId) {
        travelcare_agent.dryrun.SideEffectGuard.checkCurrent(travelcare_agent.dryrun.SideEffectOperation.HUMAN_REVIEW_WRITE);
        CurrentUser actor = currentUser();
        HumanReviewCase hrCase = repository.findByIdAndTenantId(caseId, actor.tenantId())
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Human review case not found: " + caseId));
        validateResourceGraph(hrCase.getSessionId(), hrCase.getWorkflowId(), hrCase.getRefundCaseId(), actor.tenantId());
        if (hrCase.getStatus() != HumanReviewCaseStatus.OPEN) {
            throw new BusinessException(ResultCode.HUMAN_REVIEW_STATE_CONFLICT,
                    "Human review case cannot be assigned from status " + hrCase.getStatus());
        }

        long expectedVersion = version(hrCase.getVersion());
        hrCase.setStatus(HumanReviewCaseStatus.ASSIGNED);
        hrCase.setAssignedTo(actor.userId().toString());
        hrCase.setUpdatedAt(LocalDateTime.now());

        requireOne(repository.assignIfOpen(hrCase, expectedVersion));
        hrCase.setVersion(expectedVersion + 1);

        auditService.recordAuthenticatedOperator(
                hrCase.getSessionId(),
                hrCase.getWorkflowId(),
                "ASSIGN",
                "HUMAN_REVIEW_CASE",
                hrCase.getId(),
                "{\"status\":\"ASSIGNED\",\"assignedTo\":\"" + actor.userId() + "\"}",
                "{}"
        );

        return hrCase;
    }

    @Transactional
    public HumanReviewCase resolveCase(Long caseId, HumanReviewResolution resolution, String resolutionNote) {
        travelcare_agent.dryrun.SideEffectGuard.checkCurrent(travelcare_agent.dryrun.SideEffectOperation.HUMAN_REVIEW_WRITE);
        CurrentUser actor = currentUser();
        HumanReviewCase hrCase = repository.findByIdAndTenantId(caseId, actor.tenantId())
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Human review case not found: " + caseId));
        ResourceGraph graph = validateResourceGraph(hrCase.getSessionId(), hrCase.getWorkflowId(),
                hrCase.getRefundCaseId(), actor.tenantId());
        if (hrCase.getStatus() != HumanReviewCaseStatus.OPEN
                && hrCase.getStatus() != HumanReviewCaseStatus.ASSIGNED) {
            throw new BusinessException(ResultCode.HUMAN_REVIEW_STATE_CONFLICT,
                    "Human review case cannot be resolved from status " + hrCase.getStatus());
        }

        if (resolution == HumanReviewResolution.APPROVED && !approvalAllowed(hrCase)) {
            throw new BusinessException(ResultCode.MANUAL_REFUND_VERIFICATION_REQUIRED);
        }

        long reviewVersion = version(hrCase.getVersion());
        Workflow workflow = graph.workflow();
        long workflowVersion = version(workflow.getVersion());
        RefundCase refundCase = graph.refundCase();
        long refundVersion = refundCase == null ? 0L : version(refundCase.getVersion());

        WorkflowStatus targetStatus = resolution == HumanReviewResolution.APPROVED
                ? WorkflowStatus.RESPONDED : WorkflowStatus.FAILED;
        workflow.transitionTo(targetStatus, "RESOLVED", "{\"resolution\":\"" + resolution
                + "\",\"note\":\"" + (resolutionNote == null ? "" : resolutionNote) + "\"}");
        requireOne(workflowRepository.transitionIfCurrent(workflow, workflowVersion,
                List.of(WorkflowStatus.NEED_HUMAN)));
        workflow.setVersion(workflowVersion + 1);

        hrCase.setStatus(HumanReviewCaseStatus.RESOLVED);
        hrCase.setResolution(resolution);
        hrCase.setResolutionNote(resolutionNote);
        hrCase.setResolvedBy(actor.userId().toString());
        hrCase.setResolvedAt(LocalDateTime.now());
        hrCase.setUpdatedAt(LocalDateTime.now());

        requireOne(repository.resolveIfCurrent(hrCase, reviewVersion));
        hrCase.setVersion(reviewVersion + 1);

        if (refundCase != null) {
            RefundCaseStatus refundTargetStatus = resolution == HumanReviewResolution.APPROVED
                    ? RefundCaseStatus.ELIGIBLE : RefundCaseStatus.INELIGIBLE;
            refundCase.setStatus(refundTargetStatus);
            refundCase.setUpdatedAt(LocalDateTime.now());
            requireOne(refundCaseRepository.decideIfNeedHuman(refundCase, refundVersion));
            refundCase.setVersion(refundVersion + 1);
        }

        // Audit resolve action
        auditService.recordAuthenticatedOperator(
                hrCase.getSessionId(),
                hrCase.getWorkflowId(),
                "RESOLVE",
                "HUMAN_REVIEW_CASE",
                hrCase.getId(),
                "{\"status\":\"RESOLVED\",\"resolution\":\"" + resolution + "\"}",
                "{\"resolutionNote\":\"" + (resolutionNote == null ? "" : resolutionNote) + "\"}"
        );

        // User visibility check
        if (resolutionNote != null && !resolutionNote.trim().isEmpty()) {
            eventService.appendMessage(
                    hrCase.getSessionId(),
                    SessionEventRole.ASSISTANT,
                    resolutionNote,
                    "{\"source\":\"human_review\",\"caseId\":\"" + hrCase.getId() + "\"}"
            );
        }

        return hrCase;
    }

    public List<HumanReviewCase> listOpenCases() {
        return repository.findByTenantIdAndStatus(currentUser().tenantId(), HumanReviewCaseStatus.OPEN);
    }

    public Optional<HumanReviewCase> getCase(Long caseId) {
        CurrentUser actor = currentUser();
        Optional<HumanReviewCase> result = repository.findByIdAndTenantId(caseId, actor.tenantId());
        result.ifPresent(hrCase -> validateResourceGraph(hrCase.getSessionId(), hrCase.getWorkflowId(),
                hrCase.getRefundCaseId(), actor.tenantId()));
        return result;
    }

    public HumanHandoffContextPacket getContextPacket(Long caseId) {
        CurrentUser actor = currentUser();
        HumanReviewCase hrCase = repository.findByIdAndTenantId(caseId, actor.tenantId())
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Human review case not found: " + caseId));
        validateResourceGraph(hrCase.getSessionId(), hrCase.getWorkflowId(), hrCase.getRefundCaseId(), actor.tenantId());
        return contextPacket(hrCase);
    }

    public HumanHandoffContextPacket contextPacket(HumanReviewCase hrCase) {
        if (contextPacketBuilder == null) {
            return null;
        }
        BuildOutcome<HumanHandoffContextPacket> outcome = contextPacketBuilder.fromStoredEvidenceOutcome(hrCase);
        recordDegradation(hrCase.getSessionId(), hrCase.getWorkflowId(), hrCase.getId(), outcome);
        return outcome.value();
    }

    public boolean approvalAllowed(HumanReviewCase hrCase) {
        return HumanReviewApprovalPolicy.allows(hrCase, contextPacket(hrCase));
    }

    public Optional<HumanReviewCase> findByWorkflowId(Long workflowId) {
        CurrentUser actor = currentUser();
        return repository.findByWorkflowIdAndTenantId(workflowId, actor.tenantId());
    }

    private CurrentUser currentUser() {
        if (authorizationService == null) {
            throw new IllegalStateException("AuthorizationService is required for Human Review access");
        }
        return authorizationService.currentUser();
    }

    private ResourceGraph validateResourceGraph(Long sessionId, Long workflowId, Long refundCaseId,
                                                String expectedTenantId) {
        if (sessionRepository == null) {
            throw new IllegalStateException("SessionRepository is required for Human Review resource validation");
        }
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Human review resource not found"));
        if (expectedTenantId != null && !expectedTenantId.equals(session.getTenantId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "Human review resource not found");
        }
        Workflow workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Human review resource not found"));
        if (!sessionId.equals(workflow.getSessionId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "Human review resource not found");
        }
        RefundCase refundCase = null;
        if (refundCaseId != null) {
            refundCase = refundCaseRepository.findById(refundCaseId)
                    .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Human review resource not found"));
            if (!session.getTenantId().equals(refundCase.getTenantId())
                    || !workflowId.equals(refundCase.getWorkflowId())) {
                throw new BusinessException(ResultCode.NOT_FOUND, "Human review resource not found");
            }
        }
        return new ResourceGraph(session, workflow, refundCase);
    }

    private record ResourceGraph(Session session, Workflow workflow, RefundCase refundCase) {}

    private static long version(Long value) {
        if (value == null) throw new BusinessException(ResultCode.DATA_INTEGRITY_CONFLICT);
        return value;
    }

    private static void requireOne(int rows) {
        if (rows == 0) throw new BusinessException(ResultCode.CONCURRENT_STATE_CONFLICT);
        if (rows != 1) throw new BusinessException(ResultCode.DATA_INTEGRITY_CONFLICT);
    }

    private String contextPacketJson(Long sessionId, Long workflowId, Long refundCaseId, String caseType,
            String priority, String reasonCode, String evidenceJson) {
        if (contextPacketBuilder == null) {
            return evidenceJson == null ? "{}" : evidenceJson;
        }
        BuildOutcome<HumanHandoffContextPacket> outcome = contextPacketBuilder.buildOutcome(
                new HumanHandoffContextPacketBuilder.Request(sessionId, workflowId, refundCaseId, caseType,
                        priority, reasonCode, evidenceJson));
        recordDegradation(sessionId, workflowId, refundCaseId, outcome);
        return contextPacketBuilder.toJson(outcome.value());
    }

    private void recordDegradation(Long sessionId, Long workflowId, Long targetId,
            BuildOutcome<HumanHandoffContextPacket> outcome) {
        if (degradationRecorder != null) degradationRecorder.record("HANDOFF_PACKET_PARTIAL_BUILD",
                sessionId, workflowId, "HANDOFF_PACKET", targetId, outcome.completeness(), outcome.availableTraceId());
    }
}
