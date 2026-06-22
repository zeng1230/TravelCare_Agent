package travelcare_agent.agent.safety;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ModelSafetyGate {
    private final ModelOutputValidator validator;
    private final UnsafeCommitmentDetector commitmentDetector;
    private final SensitiveDataLeakageDetector leakageDetector;
    private final ToolProposalGuard toolGuard;
    private final CitationRequirementChecker citationChecker;
    private final SafeFallbackAnswerGenerator fallbackGenerator;

    public ModelSafetyGate() {
        this(new ModelOutputValidator(), new UnsafeCommitmentDetector(), new SensitiveDataLeakageDetector(),
                new ToolProposalGuard(), new CitationRequirementChecker(), new SafeFallbackAnswerGenerator());
    }

    public ModelSafetyGate(ModelOutputValidator validator,
            UnsafeCommitmentDetector commitmentDetector,
            SensitiveDataLeakageDetector leakageDetector,
            ToolProposalGuard toolGuard,
            CitationRequirementChecker citationChecker,
            SafeFallbackAnswerGenerator fallbackGenerator) {
        this.validator = validator;
        this.commitmentDetector = commitmentDetector;
        this.leakageDetector = leakageDetector;
        this.toolGuard = toolGuard;
        this.citationChecker = citationChecker;
        this.fallbackGenerator = fallbackGenerator;
    }

    public ModelSafetyDecision evaluate(StructuredModelOutput output, ModelSafetyContext context) {
        String structuralReason = validator.structuralReason(output, context);
        if (structuralReason != null) return decision(ModelSafetyDecisionType.FALLBACK, structuralReason, context);

        String draft = output.answerDraft();
        if (commitmentDetector.conflictsWithAuthority(draft, context.authoritativeDecision())) {
            return blocked("AUTHORITATIVE_DECISION_CONFLICT", RiskSeverity.CRITICAL, context);
        }
        if (commitmentDetector.treatsRagAsOrderFact(draft, context.knowledgeOperation())) {
            return blocked("RAG_AS_ORDER_FACT", RiskSeverity.HIGH, context);
        }
        if (commitmentDetector.containsUnsafeCommitment(draft)) {
            return blocked("UNSAFE_COMMITMENT", RiskSeverity.CRITICAL, context);
        }
        if (leakageDetector.containsSensitiveLeakage(draft)
                || leakageDetector.containsSensitiveLeakage(output.refusalReason())
                || leakageDetector.containsSensitiveLeakage(output.handoffReason())) {
            return blocked("SENSITIVE_DATA_LEAKAGE", RiskSeverity.CRITICAL, context);
        }
        if (!toolGuard.isAllowed(output.toolProposal())) {
            return blocked("UNAUTHORIZED_TOOL_PROPOSAL", RiskSeverity.CRITICAL, context);
        }

        String citationReason = citationChecker.rejectionReason(output, context);
        if ("RAG_BUSINESS_OVERRIDE".equals(citationReason)) {
            return blocked(citationReason, RiskSeverity.HIGH, context);
        }
        if (citationReason != null) return decision(ModelSafetyDecisionType.FALLBACK, citationReason, context);

        if (output.confidence() < ModelOutputValidator.MIN_CONFIDENCE) {
            if (validator.missingRequiredOrderReference(output, context)) {
                return decision(ModelSafetyDecisionType.CLARIFY, "LOW_CONFIDENCE_MISSING_SLOT", context);
            }
            return decision(ModelSafetyDecisionType.FALLBACK, "LOW_CONFIDENCE", context);
        }
        if (validator.missingRequiredAnswerDraft(output, context)) {
            return decision(ModelSafetyDecisionType.FALLBACK, "MISSING_ANSWER_DRAFT", context);
        }
        return new ModelSafetyDecision(ModelSafetyDecisionType.ALLOW, "SAFE_OUTPUT", List.of(), draft);
    }

    private ModelSafetyDecision blocked(String code, RiskSeverity severity, ModelSafetyContext context) {
        return new ModelSafetyDecision(ModelSafetyDecisionType.BLOCK, code,
                List.of(new RiskFlag(code, severity, code)),
                fallbackGenerator.generate(ModelSafetyDecisionType.BLOCK, context));
    }

    private ModelSafetyDecision decision(ModelSafetyDecisionType type, String reason, ModelSafetyContext context) {
        return new ModelSafetyDecision(type, reason, List.of(), fallbackGenerator.generate(type, context));
    }
}
