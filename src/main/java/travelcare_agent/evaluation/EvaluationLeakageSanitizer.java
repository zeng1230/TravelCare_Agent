package travelcare_agent.evaluation;

import travelcare_agent.trace.RedactionBoundary;

public final class EvaluationLeakageSanitizer {
    private EvaluationLeakageSanitizer() {
    }

    public static boolean containsSensitiveLeakage(Object value) {
        return RedactionBoundary.service().containsSensitiveLeakage(value);
    }

    public static String redact(String value) {
        return value == null || value.isBlank() ? value : RedactionBoundary.service().redact(value).value();
    }
}
