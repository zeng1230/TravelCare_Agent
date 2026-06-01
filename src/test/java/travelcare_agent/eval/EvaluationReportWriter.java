package travelcare_agent.eval;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Component
public class EvaluationReportWriter {

    private static final Path REPORT_PATH = Path.of("target", "evaluation", "evaluation_report.md");

    public Path write(List<EvaluationCaseResult> results, String regressionStatus) {
        try {
            Files.createDirectories(REPORT_PATH.getParent());
            Files.writeString(REPORT_PATH, markdown(results, regressionStatus));
            return REPORT_PATH;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to write evaluation report", ex);
        }
    }

    private String markdown(List<EvaluationCaseResult> inputResults, String regressionStatus) {
        List<EvaluationCaseResult> results = inputResults.stream()
                .sorted(Comparator.comparing(EvaluationCaseResult::caseId))
                .toList();
        Metrics metrics = Metrics.from(results, regressionStatus);

        StringBuilder report = new StringBuilder();
        report.append("# TravelCare Agent Evaluation Report\n\n");
        report.append("## Summary\n");
        report.append("- Total cases: ").append(metrics.totalCases()).append("\n");
        report.append("- Passed: ").append(metrics.passedCases()).append("\n");
        report.append("- Failed: ").append(metrics.failedCases()).append("\n");
        report.append("- Generated at: ").append(LocalDateTime.now()).append("\n\n");

        report.append("## Metrics\n");
        report.append("- RAG hit rate: ").append(metrics.ragHitRate()).append("\n");
        report.append("- Memory usage rate: ").append(metrics.memoryUsageRate()).append("\n");
        report.append("- Unsafe override count: ").append(metrics.unsafeOverrideCount()).append("\n");
        report.append("- AgentRun success count: ").append(metrics.agentRunSuccessCount()).append("\n");
        report.append("- AgentRun failed count: ").append(metrics.agentRunFailedCount()).append("\n");
        report.append("- Regression status: ").append(metrics.regressionStatus()).append("\n\n");

        report.append("## Cases\n");
        for (EvaluationCaseResult result : results) {
            report.append("### ").append(result.caseId()).append(" ").append(result.description()).append("\n");
            report.append("- Status: ").append(result.passed() ? "PASSED" : "FAILED").append("\n");
            report.append("- AgentRun ID: ").append(value(result.agentRunId())).append("\n");
            report.append("- AgentRun status: ").append(value(result.agentRunStatus())).append("\n");
            report.append("- Replay endpoint: ").append(value(result.replayEndpoint())).append("\n");
            report.append("- Input: ").append(value(result.inputMessage())).append("\n");
            report.append("- Expected workflow status: ").append(value(result.expectedWorkflowStatus())).append("\n");
            report.append("- Actual workflow status: ").append(value(result.actualWorkflowStatus())).append("\n");
            report.append("- Expected refund decision: ").append(value(result.expectedRefundDecision())).append("\n");
            report.append("- Actual refund decision: ").append(value(result.actualRefundDecision())).append("\n");
            report.append("- Expected retrieval hit: ").append(result.expectedRetrievalHit()).append("\n");
            report.append("- Actual retrieval chunks: ").append(result.actualRetrievalChunkIds()).append("\n");
            report.append("- Expected memory usage: ").append(result.expectedMemoryUsage()).append("\n");
            report.append("- Actual memory IDs: ").append(result.actualMemoryIds()).append("\n");
            report.append("- Expected audit actions: ").append(result.expectedAuditActions()).append("\n");
            report.append("- Actual audit actions: ").append(result.actualAuditActions()).append("\n");
            report.append("- Expected no unsafe override: ").append(result.expectedNoUnsafeOverride()).append("\n");
            report.append("- Actual unsafe override: ").append(result.actualUnsafeOverride()).append("\n");
            report.append("- Failure reason: ").append(value(result.failureReason())).append("\n\n");
        }
        return report.toString();
    }

    private static String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public record Metrics(
            int totalCases,
            long passedCases,
            long failedCases,
            String ragHitRate,
            String memoryUsageRate,
            long unsafeOverrideCount,
            long agentRunSuccessCount,
            long agentRunFailedCount,
            String regressionStatus
    ) {
        static Metrics from(List<EvaluationCaseResult> results, String regressionStatus) {
            int total = results.size();
            long passed = results.stream().filter(EvaluationCaseResult::passed).count();
            long failed = total - passed;
            long expectedRag = results.stream().filter(EvaluationCaseResult::expectedRetrievalHit).count();
            long actualRag = results.stream()
                    .filter(EvaluationCaseResult::expectedRetrievalHit)
                    .filter(result -> result.actualRetrievalChunkIds() != null && !result.actualRetrievalChunkIds().isEmpty())
                    .count();
            long expectedMemory = results.stream().filter(EvaluationCaseResult::expectedMemoryUsage).count();
            long actualMemory = results.stream()
                    .filter(EvaluationCaseResult::expectedMemoryUsage)
                    .filter(result -> result.actualMemoryIds() != null && !result.actualMemoryIds().isEmpty())
                    .count();
            long unsafeOverrides = results.stream().filter(EvaluationCaseResult::actualUnsafeOverride).count();
            long agentRunSuccess = results.stream().filter(result -> "SUCCEEDED".equals(result.agentRunStatus())).count();
            long agentRunFailed = results.stream()
                    .filter(result -> result.agentRunStatus() != null && result.agentRunStatus().startsWith("FAILED"))
                    .count();

            return new Metrics(
                    total,
                    passed,
                    failed,
                    rate(actualRag, expectedRag),
                    rate(actualMemory, expectedMemory),
                    unsafeOverrides,
                    agentRunSuccess,
                    agentRunFailed,
                    regressionStatus
            );
        }

        private static String rate(long numerator, long denominator) {
            if (denominator == 0) {
                return "N/A";
            }
            BigDecimal percent = BigDecimal.valueOf(numerator)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
            return percent.stripTrailingZeros().toPlainString() + "%";
        }
    }
}
