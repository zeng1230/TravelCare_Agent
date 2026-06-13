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

    private static final Object LOCK = new Object();
    private static final java.util.Set<Path> INITIALIZED_DIRECTORIES = new java.util.HashSet<>();
    private final Path reportPath;

    public EvaluationReportWriter() {
        this(Path.of("target", "evaluation", "evaluation_report.md"));
    }

    EvaluationReportWriter(Path reportPath) {
        this.reportPath = reportPath;
    }

    public Path write(List<EvaluationCaseResult> results, String regressionStatus) {
        return write("default", results, regressionStatus);
    }

    public Path write(String suiteId, List<EvaluationCaseResult> results, String regressionStatus) {
        String safeSuite = suiteId == null || suiteId.isBlank() ? "default" : suiteId.replaceAll("[^A-Za-z0-9_-]", "_");
        Path suitePath = reportPath.getParent().resolve(safeSuite + "_report.md");
        synchronized (LOCK) {
        try {
            Files.createDirectories(reportPath.getParent());
            initializeOutputDirectory();
            atomicWrite(suitePath, markdown(results, regressionStatus));
            rebuildAggregate();
            return suitePath;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to write evaluation report", ex);
        }
        }
    }

    private void initializeOutputDirectory() throws IOException {
        Path directory = reportPath.getParent().toAbsolutePath().normalize();
        if (!INITIALIZED_DIRECTORIES.add(directory)) return;
        try (var files = Files.list(reportPath.getParent())) {
            for (Path path : files.filter(path -> path.getFileName().toString().endsWith("_report.md")).toList()) {
                Files.deleteIfExists(path);
            }
        }
        Files.deleteIfExists(reportPath);
    }

    private void rebuildAggregate() throws IOException {
        StringBuilder aggregate = new StringBuilder("# TravelCare Agent Aggregated Evaluation Report\n\n");
        try (var files = Files.list(reportPath.getParent())) {
            for (Path path : files.filter(path -> path.getFileName().toString().endsWith("_report.md"))
                    .filter(path -> !path.equals(reportPath))
                    .sorted().toList()) {
                aggregate.append("## Suite: ").append(path.getFileName().toString().replace("_report.md", ""))
                        .append("\n\n").append(Files.readString(path)).append("\n");
            }
        }
        atomicWrite(reportPath, aggregate.toString());
    }

    private void atomicWrite(Path target, String content) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, content);
        try {
            Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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

        report.append("## Agent Runs\n");
        for (EvaluationCaseResult result : results) {
            report.append("- ").append(result.caseId())
                    .append(": agentRunId=").append(value(result.agentRunId()))
                    .append(", status=").append(value(result.agentRunStatus()))
                    .append(", replay=").append(value(result.replayEndpoint()))
                    .append("\n");
        }
        report.append("\n");

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
