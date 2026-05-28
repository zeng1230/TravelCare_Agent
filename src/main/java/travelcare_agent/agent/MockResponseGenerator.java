package travelcare_agent.agent;

import org.springframework.stereotype.Component;
import travelcare_agent.enums.WorkflowStatus;
import travelcare_agent.workflow.WorkflowEngine;

@Component
public class MockResponseGenerator {

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
            return "Rule: order number is required before refund rules can be checked.";
        }
        return "Rule: paid, refundable orders departing after 24 hours can be reviewed.";
    }

    private static String displayOrderNo(String orderNo) {
        return orderNo == null || orderNo.isBlank() ? "the provided message" : orderNo;
    }
}
