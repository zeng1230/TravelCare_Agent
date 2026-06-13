package travelcare_agent.eval;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Stage7BEvaluationSuiteTest {
    @Test
    void writesDryRunAndDiffCaseWithoutOverwritingAggregate() throws Exception {
        EvaluationCaseResult result = new EvaluationCaseResult(
                "STAGE7B-001", "Diagnostic dry run creates a side-effect-free trace and diff",
                "Can I refund order ORD-1001?", "RESPONDED", "RESPONDED", "ELIGIBLE", "ELIGIBLE",
                false, List.of(), false, List.of(), List.of(), List.of(), true, false,
                null, "SUCCEEDED", "/api/agent-traces/{traceId}/dry-run", true, null
        );
        Path suite = new EvaluationReportWriter().write("stage7b", List.of(result), "PASS");
        assertThat(Files.readString(suite)).contains("STAGE7B-001", "Diagnostic dry run");
        String aggregate = Files.readString(Path.of("target", "evaluation", "evaluation_report.md"));
        assertThat(aggregate).contains("Suite: stage7b", "STAGE7B-001");
        assertThat(aggregate).doesNotContain("Suite: evaluation");
    }
}
