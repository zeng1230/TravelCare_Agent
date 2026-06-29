package travelcare_agent.evaluation.scoring;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class RequiredEventScorer implements EvaluationScorer {
    public String name() {
        return "requiredEvents";
    }

    public ScoreResult score(EvaluationScoringContext c) {
        var n = c.expectation() == null ? null : c.expectation().get("requiredEvents");
        if (n == null || !n.isArray()) return ScoreResult.skipped(name());
        List<String> e = new ArrayList<>();
        n.forEach(x -> e.add(x.asText()));
        List<String> missing = e.stream().filter(x -> !c.eventNames().contains(x)).toList();
        return ScoreResult.of(name(), missing.isEmpty(), e, c.eventNames(), missing.isEmpty() ? "required events present" : "missing events: " + missing);
    }
}
