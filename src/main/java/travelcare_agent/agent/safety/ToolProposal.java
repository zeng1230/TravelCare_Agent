package travelcare_agent.agent.safety;

public record ToolProposal(
        String toolName,
        String actionType,
        ToolArguments arguments,
        Boolean requiresConfirmation,
        String idempotencyScope
) {
}
