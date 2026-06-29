package travelcare_agent.evaluation.scoring;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class TraceStructureScorer implements EvaluationScorer {
    public String name() {
        return "traceStructure";
    }

    public ScoreResult score(EvaluationScoringContext c) {
        var n = c.expectation() == null ? null : c.expectation().get("requiredSpanTypes");
        if (n == null || !n.isArray()) return ScoreResult.skipped(name());
        List<String> e = new ArrayList<>();
        n.forEach(x -> e.add(x.asText()));
        List<String> missing = e.stream().filter(x -> !c.spanTypes().contains(x)).toList();
        return ScoreResult.of(name(), missing.isEmpty(), e, c.spanTypes(), missing.isEmpty() ? "required span types present" : "missing span types: " + missing);
    }
}
