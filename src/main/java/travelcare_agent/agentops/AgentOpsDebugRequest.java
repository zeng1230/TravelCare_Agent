package travelcare_agent.agentops;

public record AgentOpsDebugRequest(
        Long sessionId,
        Long workflowId,
        String question,
        Boolean dryRun
) {
}
