package travelcare_agent.policy;

import org.springframework.stereotype.Component;
import travelcare_agent.enums.OrderStatus;
import travelcare_agent.enums.RefundCaseStatus;
import travelcare_agent.workflow.workflows.OrderRefundInquiryWorkflow;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import travelcare_agent.trace.*;

@Component
public class RefundEligibilityPolicy {

    private final Clock clock;
    private final TraceService traceService;

    public RefundEligibilityPolicy() {
        this(Clock.systemDefaultZone(), null);
    }

    public RefundEligibilityPolicy(Clock clock) {
        this(clock, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RefundEligibilityPolicy(TraceService traceService, Clock clock) {
        this(clock, traceService);
    }

    private RefundEligibilityPolicy(Clock clock, TraceService traceService) {
        this.clock = clock;
        this.traceService = traceService;
    }

    public RefundEligibilityDecision evaluate(OrderRefundInquiryWorkflow.OrderSnapshot order, Long currentUserId) {
        return evaluateAt(order, currentUserId, LocalDateTime.now(clock));
    }

    public RefundEligibilityDecision evaluateAt(OrderRefundInquiryWorkflow.OrderSnapshot order, Long currentUserId,
            LocalDateTime evaluatedAt) {
        TraceService.SpanHandle span = traceService == null ? TraceService.SpanHandle.unavailable()
                : traceService.startSpan(SpanType.POLICY, "refund-eligibility", Map.of("orderNo", order.orderNo()));
        try {
            if (traceService != null) {
                Map<String, Object> inputSnapshot = new java.util.LinkedHashMap<>();
                inputSnapshot.put("currentUserId", currentUserId);
                inputSnapshot.put("evaluatedAt", evaluatedAt);
                inputSnapshot.put("order", order);
                traceService.recordCurrentSnapshot(TraceSnapshotType.POLICY_INPUT,
                        "POLICY", "refund-eligibility", inputSnapshot);
            }
            RefundEligibilityDecision result = evaluateInternal(order, currentUserId, evaluatedAt);
            if (traceService != null) traceService.recordCurrentSnapshot(TraceSnapshotType.POLICY_DECISION,
                    "POLICY", "refund-eligibility", Map.of(
                            "decision", result.status().name(),
                            "reason", result.reason(),
                            "refundAmount", result.refundAmount() == null ? "" : result.refundAmount(),
                            "policyResult", result.policyResultJson()
                    ));
            if (traceService != null) traceService.finishSpanSuccess(span, "POLICY_DECISION:" + result.status().name(),
                    Map.of("decision", result.status().name(), "reason", result.reason()));
            return result;
        } catch (RuntimeException ex) {
            if (traceService != null) traceService.finishSpanFailure(span, "POLICY_FAILED", ex, Map.of());
            throw ex;
        }
    }

    private RefundEligibilityDecision evaluateInternal(OrderRefundInquiryWorkflow.OrderSnapshot order, Long currentUserId,
            LocalDateTime evaluatedAt) {
        if (currentUserId == null || !currentUserId.equals(order.userId())) {
            return decision(
                    RefundCaseStatus.NEED_HUMAN,
                    "order ownership could not be verified",
                    null,
                    "FAIL",
                    "NOT_EVALUATED",
                    "NOT_EVALUATED",
                    "NOT_EVALUATED"
            );
        }
        if (order.status() != OrderStatus.PAID) {
            return decision(
                    RefundCaseStatus.INELIGIBLE,
                    "order status is " + order.status().name(),
                    null,
                    "PASS",
                    "FAIL",
                    "NOT_EVALUATED",
                    "NOT_EVALUATED"
            );
        }
        if (!order.refundable()) {
            return decision(
                    RefundCaseStatus.INELIGIBLE,
                    "order is marked non-refundable",
                    null,
                    "PASS",
                    "PASS",
                    "FAIL",
                    "NOT_EVALUATED"
            );
        }
        long hoursUntilDeparture = ChronoUnit.HOURS.between(evaluatedAt, order.departureTime());
        if (hoursUntilDeparture <= 24) {
            return decision(
                    RefundCaseStatus.INELIGIBLE,
                    "departure is within 24 hours",
                    null,
                    "PASS",
                    "PASS",
                    "PASS",
                    "FAIL"
            );
        }
        return decision(
                RefundCaseStatus.ELIGIBLE,
                "eligible",
                order.paidAmount(),
                "PASS",
                "PASS",
                "PASS",
                "PASS"
        );
    }

    private static RefundEligibilityDecision decision(
            RefundCaseStatus status,
            String reason,
            BigDecimal refundAmount,
            String ownership,
            String orderStatus,
            String refundable,
            String departureTime
    ) {
        String policyJson = "{\"decision\":\"" + status.name()
                + "\",\"reason\":\"" + escape(reason)
                + "\",\"checks\":{\"ownership\":\"" + ownership
                + "\",\"orderStatus\":\"" + orderStatus
                + "\",\"refundable\":\"" + refundable
                + "\",\"departureTime\":\"" + departureTime + "\"}}";
        return new RefundEligibilityDecision(status, reason, refundAmount, policyJson);
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
