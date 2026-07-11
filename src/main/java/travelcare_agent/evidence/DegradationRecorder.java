package travelcare_agent.evidence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import travelcare_agent.audit.AuditService;
import travelcare_agent.trace.RedactionBoundary;
import travelcare_agent.trace.TraceEventType;
import travelcare_agent.trace.TraceService;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class DegradationRecorder {
    private static final Logger log = LoggerFactory.getLogger(DegradationRecorder.class);
    private final AuditService auditService;
    private final TraceService traceService;

    public DegradationRecorder(AuditService auditService, TraceService traceService) {
        this.auditService = auditService;
        this.traceService = traceService;
    }

    public void record(String action, Long sessionId, Long workflowId, String targetType, Long targetId,
            CompletenessAssessment assessment, String traceId) {
        if (assessment == null || assessment.status() == CompletenessStatus.COMPLETE) return;
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("completenessStatus", assessment.status().name());
        evidence.put("missingSections", assessment.missingSections());
        evidence.put("riskWarnings", assessment.riskWarnings());
        try {
            String safeEvidence = RedactionBoundary.service().redactObject(evidence).value();
            auditService.recordEvidenceDegradation(sessionId, workflowId, action, targetType, targetId, safeEvidence);
        } catch (RuntimeException ex) {
            log.warn("Evidence degradation audit write failed for action {} ({})",
                    RedactionBoundary.sanitizeLogField(action, 80), ex.getClass().getSimpleName());
        }
        if (traceId != null && traceService != null) {
            traceService.recordEvent(traceId, null, TraceEventType.PARTIAL_BUILD_DEGRADED,
                    "partial-build-degraded", evidence);
        }
    }
}
