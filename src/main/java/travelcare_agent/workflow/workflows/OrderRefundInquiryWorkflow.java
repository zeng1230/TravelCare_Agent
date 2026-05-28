package travelcare_agent.workflow.workflows;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import travelcare_agent.adapter.order.MockOrderAdapter;
import travelcare_agent.audit.AuditService;
import travelcare_agent.enums.OrderStatus;
import travelcare_agent.enums.RefundCaseStatus;
import travelcare_agent.policy.RefundEligibilityDecision;
import travelcare_agent.policy.RefundEligibilityPolicy;
import travelcare_agent.refund.entity.RefundCase;
import travelcare_agent.refund.repository.RefundCaseRepository;
import travelcare_agent.tool.ToolService;
import travelcare_agent.workflow.ExecutableWorkflow;
import travelcare_agent.workflow.WorkflowEngine;
import travelcare_agent.workflow.entity.WorkflowStep;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

@Component
public class OrderRefundInquiryWorkflow implements ExecutableWorkflow {

    public static final String TYPE = "order_refund_inquiry";

    private final MockOrderAdapter mockOrderAdapter;
    private final ToolService toolService;
    private final Clock clock;
    private final RefundEligibilityPolicy refundEligibilityPolicy;
    private final RefundCaseRepository refundCaseRepository;
    private final AuditService auditService;

    @Autowired
    public OrderRefundInquiryWorkflow(
            MockOrderAdapter mockOrderAdapter,
            ToolService toolService,
            RefundEligibilityPolicy refundEligibilityPolicy,
            RefundCaseRepository refundCaseRepository,
            AuditService auditService
    ) {
        this(mockOrderAdapter, toolService, Clock.systemDefaultZone(), refundEligibilityPolicy, refundCaseRepository, auditService);
    }

    public OrderRefundInquiryWorkflow(MockOrderAdapter mockOrderAdapter, ToolService toolService) {
        this(mockOrderAdapter, toolService, Clock.systemDefaultZone());
    }

    public OrderRefundInquiryWorkflow(MockOrderAdapter mockOrderAdapter, ToolService toolService, Clock clock) {
        this(mockOrderAdapter, toolService, clock, new RefundEligibilityPolicy(clock), new RefundCaseRepository() {
            @Override
            public RefundCase save(RefundCase refundCase) { return refundCase; }
            @Override
            public Optional<RefundCase> findById(Long id) { return Optional.empty(); }
            @Override
            public Optional<RefundCase> findByWorkflowId(Long workflowId) { return Optional.empty(); }
        }, new AuditService(auditLog -> auditLog));
    }

    public OrderRefundInquiryWorkflow(
            MockOrderAdapter mockOrderAdapter,
            ToolService toolService,
            Clock clock,
            RefundEligibilityPolicy refundEligibilityPolicy,
            RefundCaseRepository refundCaseRepository,
            AuditService auditService
    ) {
        this.mockOrderAdapter = mockOrderAdapter;
        this.toolService = toolService;
        this.clock = clock;
        this.refundEligibilityPolicy = refundEligibilityPolicy;
        this.refundCaseRepository = refundCaseRepository;
        this.auditService = auditService;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public WorkflowEngine.WorkflowResult execute(WorkflowEngine.WorkflowContext context) {
        WorkflowStep collect = context.startStep("COLLECTING_ORDER_REFERENCE", "{}");
        WorkflowEngine.WorkflowCommand command = context.command();
        if (command.orderId() == null && isBlank(command.orderNo())) {
            context.failStep(collect, "ORDER_REFERENCE_MISSING", WorkflowEngine.WorkflowContext.jsonField("message", "missing order reference"));
            auditHandoff(context, "ORDER_REFERENCE_MISSING");
            return context.needHuman("Please provide an order reference so we can check the refund rules.", "ORDER_REFERENCE_MISSING");
        }
        context.succeedStep(collect, orderReferenceJson(command));

        WorkflowStep query = context.startStep("QUERYING_ORDER", orderReferenceJson(command));
        Optional<OrderSnapshot> order;
        try {
            String toolIdempotencyKey = context.workflow().getId() + "-get-order";
            ToolService.ToolCommand toolCommand = new ToolService.ToolCommand(
                    context.workflow().getSessionId(),
                    context.workflow().getId(),
                    query.getId(),
                    "GetOrderTool",
                    toolIdempotencyKey,
                    String.valueOf(command.orderNo() != null ? command.orderNo().hashCode() : 0),
                    orderReferenceJson(command),
                    LocalDateTime.now().plusSeconds(30)
            );
            
            ToolService.ToolExecution<MockOrderAdapter.OrderSnapshot> execution = toolService.execute(
                    toolCommand,
                    MockOrderAdapter.OrderSnapshot.class,
                    () -> mockOrderAdapter.getOrder(command.orderId(), command.orderNo(), command.userId()).orElse(null)
            );
            
            if (execution.result() == null) {
                order = Optional.empty();
            } else {
                order = Optional.of(OrderSnapshot.of(
                        execution.result().orderId(),
                        execution.result().orderNo(),
                        execution.result().userId(),
                        execution.result().status(),
                        execution.result().refundable(),
                        execution.result().paidAmount(),
                        execution.result().departureTime()
                ));
            }
        } catch (RuntimeException ex) {
            context.failStep(query, "ORDER_LOOKUP_FAILED", WorkflowEngine.WorkflowContext.jsonField("message", ex.getMessage()));
            auditHandoff(context, "ORDER_LOOKUP_FAILED");
            return context.needHuman("We could not query the order right now. manual support will continue the case.", "ORDER_LOOKUP_FAILED");
        }

        if (order.isEmpty()) {
            context.failStep(query, "ORDER_NOT_FOUND", orderReferenceJson(command));
            auditHandoff(context, "ORDER_NOT_FOUND");
            return context.needHuman("We could not find this order for your account. Manual support will verify it.", "ORDER_NOT_FOUND");
        }
        context.succeedStep(query, orderJson(order.get()));
        auditOrderQuery(context, order.get());

        WorkflowStep rules = context.startStep("CHECKING_REFUND_RULES", orderJson(order.get()));
        RefundEligibilityDecision decision = refundEligibilityPolicy.evaluate(order.get(), command.userId());
        RefundCase refundCase = refundCaseRepository.save(RefundCase.create(
                command.userId(),
                order.get().orderId(),
                context.workflow().getId(),
                decision.status(),
                decision.refundAmount(),
                decision.reason(),
                decision.policyResultJson()
        ));
        context.succeedStep(rules, decisionJson(decision, refundCase));
        auditRuleCheck(context, order.get(), decision, refundCase);

        if (decision.status() == RefundCaseStatus.NEED_HUMAN) {
            auditHandoff(context, decision.reason());
            return context.needHuman("manual support will verify this refund inquiry.", decision.reason());
        }

        return context.responded(answer(order.get(), decision), decisionJson(decision));
    }

    private static String answer(OrderSnapshot order, RefundEligibilityDecision decision) {
        if (decision.status() == RefundCaseStatus.ELIGIBLE) {
            return "Order " + order.orderNo() + " is eligible for refund inquiry. Refund amount can be reviewed up to "
                    + order.paidAmount() + ".";
        }
        return "Order " + order.orderNo() + " is not eligible for refund inquiry because " + decision.reason() + ".";
    }

    private static String orderReferenceJson(WorkflowEngine.WorkflowCommand command) {
        return "{\"orderId\":\"" + value(command.orderId()) + "\",\"orderNo\":\""
                + WorkflowEngine.WorkflowContext.escape(command.orderNo()) + "\"}";
    }

    private static String orderJson(OrderSnapshot order) {
        return "{\"orderId\":\"" + order.orderId() + "\",\"orderNo\":\""
                + WorkflowEngine.WorkflowContext.escape(order.orderNo()) + "\",\"status\":\""
                + order.status().name() + "\",\"refundable\":" + order.refundable() + "}";
    }

    private static String decisionJson(RefundEligibilityDecision decision) {
        return "{\"decision\":\"" + decision.status().name() + "\",\"reason\":\""
                + WorkflowEngine.WorkflowContext.escape(decision.reason()) + "\"}";
    }

    private static String decisionJson(RefundEligibilityDecision decision, RefundCase refundCase) {
        return "{\"refundCaseId\":\"" + refundCase.getId() + "\",\"decision\":\""
                + decision.status().name() + "\",\"reason\":\""
                + WorkflowEngine.WorkflowContext.escape(decision.reason()) + "\",\"policyResult\":"
                + decision.policyResultJson() + "}";
    }

    private void auditRuleCheck(
            WorkflowEngine.WorkflowContext context,
            OrderSnapshot order,
            RefundEligibilityDecision decision,
            RefundCase refundCase
    ) {
        auditService.recordRefundRuleCheck(
                context.workflow().getSessionId(),
                context.workflow().getId(),
                refundCase.getId(),
                decisionJson(decision, refundCase),
                "{\"orderId\":\"" + order.orderId() + "\",\"decision\":\"" + decision.status().name()
                        + "\",\"policyResult\":" + decision.policyResultJson() + "}"
        );
    }

    private void auditOrderQuery(WorkflowEngine.WorkflowContext context, OrderSnapshot order) {
        auditService.recordOrderQuery(
                context.workflow().getSessionId(),
                context.workflow().getId(),
                order.orderId(),
                orderJson(order),
                "{\"orderId\":\"" + order.orderId() + "\",\"orderNo\":\""
                        + WorkflowEngine.WorkflowContext.escape(order.orderNo()) + "\",\"status\":\""
                        + order.status().name() + "\"}"
        );
    }

    private void auditHandoff(WorkflowEngine.WorkflowContext context, String reasonCode) {
        auditService.recordHandoffRequired(
                context.workflow().getSessionId(),
                context.workflow().getId(),
                reasonCode
        );
    }

    private static String value(Long value) {
        return value == null ? "" : value.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }



    public record OrderSnapshot(
            Long orderId,
            String orderNo,
            Long userId,
            OrderStatus status,
            boolean refundable,
            BigDecimal paidAmount,
            LocalDateTime departureTime
    ) {
        public static OrderSnapshot of(
                Long orderId,
                String orderNo,
                Long userId,
                OrderStatus status,
                boolean refundable,
                BigDecimal paidAmount,
                LocalDateTime departureTime
        ) {
            return new OrderSnapshot(orderId, orderNo, userId, status, refundable, paidAmount, departureTime);
        }
    }
}
