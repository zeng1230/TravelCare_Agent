package travelcare_agent.evaluation.scoring;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class CitationSourceScorer implements EvaluationScorer {
    public String name() {
        return "citationSource";
    }

    public ScoreResult score(EvaluationScoringContext c) {
        Stage9EvaluationExpectation expectation = Stage9ScoringSupport.expectation(c);
        if (!expectation.hasCitationSourceExpectation()) return ScoreResult.skipped(name());
        if (!Stage9ScoringSupport.hasStage9Snapshots(c)) return ScoreResult.skipped(name());
        List<Long> expectedChunks = expectation.expectedCitationChunkIds() == null ? List.of() : expectation.expectedCitationChunkIds();
        List<Long> expectedDocuments = expectation.expectedCitationDocumentIds() == null ? List.of() : expectation.expectedCitationDocumentIds();
        List<Long> actualChunks = Stage9ScoringSupport.chunkIds(c.citations());
        List<Long> actualDocuments = Stage9ScoringSupport.documentIds(c.citations());
        List<Long> evidence = Stage9ScoringSupport.evidenceChunkIds(c);
        List<Long> missingChunks = expectedChunks.stream().filter(id -> !actualChunks.contains(id)).toList();
        List<Long> missingDocuments = expectedDocuments.stream().filter(id -> !actualDocuments.contains(id)).toList();
        List<Long> outsideEvidence = actualChunks.stream().filter(id -> !evidence.contains(id)).toList();
        boolean matched = missingChunks.isEmpty() && missingDocuments.isEmpty() && outsideEvidence.isEmpty();
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("chunkIds", expectedChunks);
        expected.put("documentIds", expectedDocuments);
        expected.put("mustBeEvidence", true);
        String reason = matched ? "citation sources matched retrieval evidence" : "citation source mismatch: missingChunks=" + missingChunks + ", missingDocuments=" + missingDocuments + ", outsideEvidence=" + outsideEvidence;
        return ScoreResult.of(name(), matched, expected, Stage9ScoringSupport.actual(c), reason);
    }
}
