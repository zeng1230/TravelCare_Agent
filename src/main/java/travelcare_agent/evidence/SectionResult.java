package travelcare_agent.evidence;

import java.util.List;

public record SectionResult<T>(T value, SectionStatus status, EvidenceSection missingSection,
                               List<RiskWarning> riskWarnings) {
    public SectionResult {
        riskWarnings = riskWarnings == null ? List.of() : List.copyOf(riskWarnings);
    }

    public static <T> SectionResult<T> available(T value) {
        return new SectionResult<>(value, SectionStatus.AVAILABLE, null, List.of());
    }

    public static <T> SectionResult<T> unavailable(T value, EvidenceSection section, RiskWarning... warnings) {
        return new SectionResult<>(value, SectionStatus.UNAVAILABLE, section, List.of(warnings));
    }

    public static <T> SectionResult<T> corrupted(T value, EvidenceSection section, RiskWarning... warnings) {
        return new SectionResult<>(value, SectionStatus.CORRUPTED, section, List.of(warnings));
    }

    public static <T> SectionResult<T> notApplicable(T value) {
        return new SectionResult<>(value, SectionStatus.NOT_APPLICABLE, null, List.of());
    }
}
