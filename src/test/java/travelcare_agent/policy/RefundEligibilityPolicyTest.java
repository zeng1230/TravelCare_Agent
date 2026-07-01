package travelcare_agent.policy;

import travelcare_agent.adapter.order.OrderSnapshot;

import org.junit.jupiter.api.Test;
import travelcare_agent.enums.OrderStatus;
import travelcare_agent.enums.RefundCaseStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class RefundEligibilityPolicyTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-05-08T08:00:00Z"),
            ZoneId.of("Asia/Shanghai")
    );

    private final RefundEligibilityPolicy policy = new RefundEligibilityPolicy(CLOCK);

    @Test
    void eligibleWhenOwnerPaidRefundableAndDepartureMoreThanTwentyFourHoursAway() {
        RefundEligibilityDecision decision = policy.evaluate(order(
                1001L,
                OrderStatus.PAID,
                true,
                LocalDateTime.now(CLOCK).plusHours(25)
        ), 1001L);

        assertThat(decision.status()).isEqualTo(RefundCaseStatus.ELIGIBLE);
        assertThat(decision.reason()).isEqualTo("eligible");
        assertThat(decision.policyResultJson())
                .contains("\"ownership\":\"PASS\"")
                .contains("\"orderStatus\":\"PASS\"")
                .contains("\"refundable\":\"PASS\"")
                .contains("\"departureTime\":\"PASS\"");
    }

    @Test
    void ineligibleWhenOrderDoesNotBelongToUser() {
        RefundEligibilityDecision decision = policy.evaluate(order(
                2002L,
                OrderStatus.PAID,
                true,
                LocalDateTime.now(CLOCK).plusHours(25)
        ), 1001L);

        assertThat(decision.status()).isEqualTo(RefundCaseStatus.NEED_HUMAN);
        assertThat(decision.reason()).isEqualTo("order ownership could not be verified");
        assertThat(decision.policyResultJson()).contains("\"ownership\":\"FAIL\"");
    }

    @Test
    void ineligibleWhenOrderStatusIsNotPaid() {
        RefundEligibilityDecision decision = policy.evaluate(order(
                1001L,
                OrderStatus.USED,
                true,
                LocalDateTime.now(CLOCK).plusHours(25)
        ), 1001L);

        assertThat(decision.status()).isEqualTo(RefundCaseStatus.INELIGIBLE);
        assertThat(decision.reason()).isEqualTo("order status is USED");
        assertThat(decision.policyResultJson()).contains("\"orderStatus\":\"FAIL\"");
    }

    @Test
    void ineligibleWhenOrderIsMarkedNonRefundable() {
        RefundEligibilityDecision decision = policy.evaluate(order(
                1001L,
                OrderStatus.PAID,
                false,
                LocalDateTime.now(CLOCK).plusHours(25)
        ), 1001L);

        assertThat(decision.status()).isEqualTo(RefundCaseStatus.INELIGIBLE);
        assertThat(decision.reason()).isEqualTo("order is marked non-refundable");
        assertThat(decision.policyResultJson()).contains("\"refundable\":\"FAIL\"");
    }

    @Test
    void ineligibleWhenDepartureIsWithinTwentyFourHours() {
        RefundEligibilityDecision decision = policy.evaluate(order(
                1001L,
                OrderStatus.PAID,
                true,
                LocalDateTime.now(CLOCK).plusHours(24)
        ), 1001L);

        assertThat(decision.status()).isEqualTo(RefundCaseStatus.INELIGIBLE);
        assertThat(decision.reason()).isEqualTo("departure is within 24 hours");
        assertThat(decision.policyResultJson()).contains("\"departureTime\":\"FAIL\"");
    }

    private static OrderSnapshot order(
            Long userId,
            OrderStatus status,
            boolean refundable,
            LocalDateTime departureTime
    ) {
        return new OrderSnapshot(
                10L,
                "ORD-10",
                userId,
                status,
                refundable,
                new BigDecimal("399.00"),
                departureTime
        );
    }
}
