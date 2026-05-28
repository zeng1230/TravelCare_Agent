package travelcare_agent.policy;

import org.springframework.stereotype.Component;
import travelcare_agent.enums.OrderStatus;
import travelcare_agent.enums.RefundCaseStatus;
import travelcare_agent.workflow.workflows.OrderRefundInquiryWorkflow;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Component
public class RefundEligibilityPolicy {

    private final Clock clock;

    public RefundEligibilityPolicy() {
        this(Clock.systemDefaultZone());
    }

    public RefundEligibilityPolicy(Clock clock) {
        this.clock = clock;
    }

    public RefundEligibilityDecision evaluate(OrderRefundInquiryWorkflow.OrderSnapshot order, Long currentUserId) {
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
        long hoursUntilDeparture = ChronoUnit.HOURS.between(LocalDateTime.now(clock), order.departureTime());
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
