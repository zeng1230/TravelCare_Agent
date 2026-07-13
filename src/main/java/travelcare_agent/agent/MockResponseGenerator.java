package travelcare_agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import travelcare_agent.enums.WorkflowStatus;
import travelcare_agent.workflow.WorkflowEngine;

@Component
public class MockResponseGenerator {
    private static final ObjectMapper JSON = new ObjectMapper();

    public String generate(MockIntentClassifier.IntentResult intent, WorkflowEngine.WorkflowResult workflowResult) {
        return generate(intent, workflowResult, null);
    }

    public String generate(MockIntentClassifier.IntentResult intent, WorkflowEngine.WorkflowResult workflowResult, AgentContext agentContext) {
        String rule = ruleExplanation(workflowResult);
        String baseResponse;
        if (MockIntentClassifier.ORDER_QUERY.equals(intent.intent())) {
            baseResponse = "Order query recognized for " + displayOrderNo(intent.orderNo())
                    + ". Workflow result: " + workflowResult.answer()
                    + " " + rule;
        } else {
            baseResponse = workflowResult.answer() + " " + rule;
        }

        if (agentContext == null) {
            return baseResponse;
        }

        StringBuilder sb = new StringBuilder(baseResponse);
        if (agentContext.policySnippets() != null && !agentContext.policySnippets().isEmpty()) {
            sb.append(" (Retrieved policies: ");
            for (int i = 0; i < agentContext.policySnippets().size(); i++) {
                sb.append(agentContext.policySnippets().get(i).title());
                if (i < agentContext.policySnippets().size() - 1) {
                    sb.append(", ");
                }
            }
            sb.append(")");
        }

        if (agentContext.activeMemories() != null && !agentContext.activeMemories().isEmpty()) {
            sb.append(" (Found preferences: ");
            for (int i = 0; i < agentContext.activeMemories().size(); i++) {
                sb.append(agentContext.activeMemories().get(i).getMemoryValue());
                if (i < agentContext.activeMemories().size() - 1) {
                    sb.append(", ");
                }
            }
            sb.append(")");
        }

        return sb.toString();
    }

    private static String ruleExplanation(WorkflowEngine.WorkflowResult workflowResult) {
        if (workflowResult.workflow().getStatus() == WorkflowStatus.NEED_HUMAN) {
            String reason = reasonCode(workflowResult.workflow().getStateJson());
            return switch (reason) {
                case "ORDER_REFERENCE_MISSING" ->
                        "Rule: order number is required before refund rules can be checked.";
                case "order ownership could not be verified" ->
                        "Rule: order ownership must be verified before refund eligibility can be approved.";
                case "ORDER_NOT_FOUND" ->
                        "Rule: the order must be found before refund eligibility can be checked.";
                case "ORDER_LOOKUP_FAILED" ->
                        "Rule: order facts must be available before refund eligibility can be checked.";
                default -> "Rule: manual review is required because the refund facts are incomplete.";
            };
        }
        return "Rule: paid, refundable orders departing after 24 hours can be reviewed.";
    }

    private static String reasonCode(String stateJson) {
        if (stateJson == null || stateJson.isBlank()) return "";
        try {
            return JSON.readTree(stateJson).path("reasonCode").asText("");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String displayOrderNo(String orderNo) {
        return orderNo == null || orderNo.isBlank() ? "the provided message" : orderNo;
    }
}
