package travelcare_agent.agent.safety;

import java.util.List;

public record StructuredModelOutput(
        String intent,
        Double confidence,
        ModelSlots slots,
        String answerDraft,
        List<CitationRef> citations,
        List<RiskFlag> riskFlags,
        ToolProposal toolProposal,
        String refusalReason,
        String handoffReason
) {
    public StructuredModelOutput {
        slots = slots == null ? ModelSlots.empty() : slots;
        citations = citations == null ? List.of() : List.copyOf(citations);
        riskFlags = riskFlags == null ? List.of() : List.copyOf(riskFlags);
    }
}
