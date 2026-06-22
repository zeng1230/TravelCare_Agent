package travelcare_agent.agent.safety;

public class ToolProposalGuard {
    public boolean isAllowed(ToolProposal proposal) {
        if (proposal == null) return true;
        return "GetOrderTool".equals(proposal.toolName())
                && "READ_ORDER".equals(proposal.actionType())
                && !Boolean.TRUE.equals(proposal.requiresConfirmation());
    }
}
