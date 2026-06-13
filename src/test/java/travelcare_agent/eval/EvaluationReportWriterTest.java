package travelcare_agent.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationReportWriterTest {
    @TempDir Path tempDir;

    @Test
    void aggregateNeverIncludesItsPreviousOutput() throws Exception {
        Path reportPath = tempDir.resolve("evaluation_report.md");
        EvaluationReportWriter writer = new EvaluationReportWriter(reportPath);
        writer.write("writer-a", List.of(), "PASS");
        writer.write("writer-b", List.of(), "PASS");
        writer.write("writer-c", List.of(), "PASS");

        String aggregate = Files.readString(reportPath);
        assertThat(occurrences(aggregate, "## Suite: writer-a")).isEqualTo(1);
        assertThat(occurrences(aggregate, "## Suite: writer-b")).isEqualTo(1);
        assertThat(occurrences(aggregate, "## Suite: writer-c")).isEqualTo(1);
        assertThat(aggregate).doesNotContain("## Suite: evaluation");
    }

    private static int occurrences(String value, String token) {
        return value.split(java.util.regex.Pattern.quote(token), -1).length - 1;
    }
}
