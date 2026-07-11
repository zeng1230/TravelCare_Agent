package travelcare_agent.evidence;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

public record CompletenessAssessment(CompletenessStatus status, List<String> missingSections,
                                     List<String> riskWarnings) {
    public static CompletenessAssessment derive(List<? extends SectionResult<?>> results) {
        EnumSet<EvidenceSection> missing = EnumSet.noneOf(EvidenceSection.class);
        EnumSet<RiskWarning> warnings = EnumSet.noneOf(RiskWarning.class);
        for (SectionResult<?> result : results == null ? List.<SectionResult<?>>of() : results) {
            if ((result.status() == SectionStatus.UNAVAILABLE || result.status() == SectionStatus.CORRUPTED)
                    && result.missingSection() != null) {
                missing.add(result.missingSection());
            }
            warnings.addAll(result.riskWarnings());
        }
        CompletenessStatus status = missing.contains(EvidenceSection.REFUND_CASE)
                || missing.contains(EvidenceSection.REFUND_POLICY_RESULT)
                ? CompletenessStatus.INSUFFICIENT
                : missing.isEmpty() ? CompletenessStatus.COMPLETE : CompletenessStatus.PARTIAL;
        List<String> sections = new ArrayList<>();
        for (EvidenceSection section : EvidenceSection.values()) if (missing.contains(section)) sections.add(section.name());
        List<String> risks = new ArrayList<>();
        for (RiskWarning warning : RiskWarning.values()) if (warnings.contains(warning)) risks.add(warning.name());
        return new CompletenessAssessment(status, List.copyOf(sections), List.copyOf(risks));
    }
}
