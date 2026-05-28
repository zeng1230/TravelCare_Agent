package travelcare_agent.agent;

import travelcare_agent.conversation.entity.SessionEvent;
import travelcare_agent.workflow.entity.Workflow;
import travelcare_agent.refund.entity.RefundCase;
import travelcare_agent.retrieval.service.RetrievalSnippet;
import travelcare_agent.memory.entity.AgentMemory;

import java.util.List;

public record AgentContext(
        List<SessionEvent> recentEvents,
        Workflow activeWorkflow,
        RefundCase refundCase,
        List<RetrievalSnippet> policySnippets,
        List<AgentMemory> activeMemories
) {
}
