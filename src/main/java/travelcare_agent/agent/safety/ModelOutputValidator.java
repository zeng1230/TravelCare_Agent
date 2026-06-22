package travelcare_agent.agent.safety;

public class ModelOutputValidator {
    public static final double MIN_CONFIDENCE = 0.70;

    public String structuralReason(StructuredModelOutput output, ModelSafetyContext context) {
        if (output == null || output.intent() == null || output.intent().isBlank()) return "MISSING_INTENT";
        if (!context.allowedIntents().contains(output.intent())) return "UNKNOWN_INTENT";
        return null;
    }

    public boolean missingRequiredAnswerDraft(StructuredModelOutput output, ModelSafetyContext context) {
        return ("RESPONSE_GENERATION".equals(context.operation())
                || "KNOWLEDGE_ANSWER".equals(context.operation()))
                && (output.answerDraft() == null || output.answerDraft().isBlank());
    }

    public boolean missingRequiredOrderReference(StructuredModelOutput output, ModelSafetyContext context) {
        boolean refundOperation = "REFUND_INQUIRY".equals(output.intent())
                && !context.knowledgeOperation();
        if (!context.orderReferenceRequired() && !refundOperation) return false;
        ModelSlots slots = output.slots();
        return slots == null || ((slots.orderNo() == null || slots.orderNo().isBlank()) && slots.orderId() == null);
    }
}
