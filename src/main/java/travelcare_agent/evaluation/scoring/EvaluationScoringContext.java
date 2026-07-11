package travelcare_agent.evaluation.scoring;

import com.fasterxml.jackson.databind.JsonNode;
import travelcare_agent.evaluation.entity.*;
import travelcare_agent.dryrun.*;
import travelcare_agent.human.packet.HumanHandoffContextPacket;

import java.time.Clock;
import java.util.*;

public class EvaluationScoringContext {
    public EvaluationRun run;
    public EvaluationCase evaluationCase;
    public JsonNode expectation;
    public Long sourceTraceId, dryRunTraceId, diffId;
    public DryRunResult dryRunResult;
    public TraceDiffResult diffResult;
    public SideEffectCheckResult sideEffectCheckResult;
    public String promptStubVersion, providerMode, policyDecision, workflowStatus, output, riskLevel;
    public List<String> spanTypes = List.of(), eventNames = List.of();
    public Clock clock;
    public String answerabilityStatus, answerabilityReasonCode, requiredAction;
    public JsonNode citations, rejectedCitationCandidates, answerabilityDecisionSnapshot, citationSummarySnapshot;
    public Boolean businessDecisionLocked, ragMayExplainBusinessDecision, ragMayOverrideBusinessDecision, fallbackUsed;
    public String safetyDecision, safetyReasonCode, supplierFailureCode, leakageCheckText;
    public List<String> safetyRiskFlags = List.of();
    public Boolean supplierGatewayParticipated, providerFallbackUsed;
    public HumanHandoffContextPacket handoffPacket;
    public Boolean approvalAllowed;

    public static Builder builder() {
        return new Builder();
    }

    public JsonNode expectation() {
        return expectation;
    }

    public String policyDecision() {
        return policyDecision;
    }

    public String workflowStatus() {
        return workflowStatus;
    }

    public String output() {
        return output;
    }

    public String riskLevel() {
        return riskLevel;
    }

    public List<String> spanTypes() {
        return spanTypes;
    }

    public List<String> eventNames() {
        return eventNames;
    }

    public SideEffectCheckResult sideEffectCheckResult() {
        return sideEffectCheckResult;
    }

    public String answerabilityStatus() {
        return answerabilityStatus;
    }

    public String answerabilityReasonCode() {
        return answerabilityReasonCode;
    }

    public String requiredAction() {
        return requiredAction;
    }

    public JsonNode citations() {
        return citations;
    }

    public JsonNode rejectedCitationCandidates() {
        return rejectedCitationCandidates;
    }

    public JsonNode answerabilityDecisionSnapshot() {
        return answerabilityDecisionSnapshot;
    }

    public JsonNode citationSummarySnapshot() {
        return citationSummarySnapshot;
    }

    public Boolean businessDecisionLocked() {
        return businessDecisionLocked;
    }

    public Boolean ragMayExplainBusinessDecision() {
        return ragMayExplainBusinessDecision;
    }

    public Boolean ragMayOverrideBusinessDecision() {
        return ragMayOverrideBusinessDecision;
    }

    public Boolean fallbackUsed() {
        return fallbackUsed;
    }

    public static class Builder {
        private final EvaluationScoringContext c = new EvaluationScoringContext();

        public Builder expectation(JsonNode v) {
            c.expectation = v;
            return this;
        }

        public Builder policyDecision(String v) {
            c.policyDecision = v;
            return this;
        }

        public Builder workflowStatus(String v) {
            c.workflowStatus = v;
            return this;
        }

        public Builder output(String v) {
            c.output = v;
            return this;
        }

        public Builder riskLevel(String v) {
            c.riskLevel = v;
            return this;
        }

        public Builder spanTypes(List<String> v) {
            c.spanTypes = v;
            return this;
        }

        public Builder eventNames(List<String> v) {
            c.eventNames = v;
            return this;
        }

        public Builder sideEffectCheckResult(SideEffectCheckResult v) {
            c.sideEffectCheckResult = v;
            return this;
        }

        public Builder answerabilityStatus(String v) {
            c.answerabilityStatus = v;
            return this;
        }

        public Builder answerabilityReasonCode(String v) {
            c.answerabilityReasonCode = v;
            return this;
        }

        public Builder requiredAction(String v) {
            c.requiredAction = v;
            return this;
        }

        public Builder citations(JsonNode v) {
            c.citations = v;
            return this;
        }

        public Builder rejectedCitationCandidates(JsonNode v) {
            c.rejectedCitationCandidates = v;
            return this;
        }

        public Builder answerabilityDecisionSnapshot(JsonNode v) {
            c.answerabilityDecisionSnapshot = v;
            return this;
        }

        public Builder citationSummarySnapshot(JsonNode v) {
            c.citationSummarySnapshot = v;
            return this;
        }

        public Builder businessDecisionLocked(Boolean v) {
            c.businessDecisionLocked = v;
            return this;
        }

        public Builder ragMayExplainBusinessDecision(Boolean v) {
            c.ragMayExplainBusinessDecision = v;
            return this;
        }

        public Builder ragMayOverrideBusinessDecision(Boolean v) {
            c.ragMayOverrideBusinessDecision = v;
            return this;
        }

        public Builder fallbackUsed(Boolean v) {
            c.fallbackUsed = v;
            return this;
        }

        public Builder safetyDecision(String v) {
            c.safetyDecision = v;
            return this;
        }

        public Builder safetyReasonCode(String v) {
            c.safetyReasonCode = v;
            return this;
        }

        public Builder safetyRiskFlags(List<String> v) {
            c.safetyRiskFlags = v == null ? List.of() : v;
            return this;
        }

        public Builder supplierGatewayParticipated(Boolean v) {
            c.supplierGatewayParticipated = v;
            return this;
        }

        public Builder supplierFailureCode(String v) {
            c.supplierFailureCode = v;
            return this;
        }

        public Builder providerFallbackUsed(Boolean v) {
            c.providerFallbackUsed = v;
            return this;
        }

        public Builder handoffPacket(HumanHandoffContextPacket v) {
            c.handoffPacket = v;
            return this;
        }

        public Builder leakageCheckText(String v) {
            c.leakageCheckText = v;
            return this;
        }

        public EvaluationScoringContext build() {
            return c;
        }
    }
}
