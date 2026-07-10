package travelcare_agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import travelcare_agent.dryrun.*;
import travelcare_agent.evaluation.entity.*;
import travelcare_agent.evaluation.repository.*;
import travelcare_agent.trace.TraceQueryService;
import travelcare_agent.trace.entity.TraceRun;
import travelcare_agent.trace.repository.TraceRunRepository;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EvaluationRunnerCaseIsolationTest {
    @Test
    void errorInOneCaseDoesNotPreventFollowingCaseFromBeingSaved() throws Exception {
        EvaluationDataset dataset = new EvaluationDataset();
        dataset.setId(1L);
        dataset.setVersion(1);
        dataset.setDatasetKey("pr4c");
        dataset.setStatus("ACTIVE");
        EvaluationCase broken = evaluationCase(11L, "broken_case", 101L);
        EvaluationCase following = evaluationCase(12L, "following_case", 102L);

        EvaluationDatasetRepository datasets = mock(EvaluationDatasetRepository.class);
        when(datasets.findById(1L)).thenReturn(Optional.of(dataset));
        EvaluationCaseRepository cases = mock(EvaluationCaseRepository.class);
        when(cases.findEnabledCasesByDatasetId(1L)).thenReturn(List.of(broken, following));
        RunRepository runs = new RunRepository();
        ResultRepository results = new ResultRepository();
        TraceRunRepository traceRuns = mock(TraceRunRepository.class);
        TraceRun followingTrace = new TraceRun();
        followingTrace.setId(102L);
        followingTrace.setTraceId("following-trace");
        when(traceRuns.findById(101L)).thenReturn(Optional.empty());
        when(traceRuns.findById(102L)).thenReturn(Optional.of(followingTrace));
        DryRunReadinessChecker readiness = mock(DryRunReadinessChecker.class);
        when(readiness.check("following-trace", "mock"))
                .thenReturn(DryRunReadinessResult.rejected(List.of("POLICY_INPUT.evaluatedAt")));
        EvaluationSideEffectGuard sideEffects = mock(EvaluationSideEffectGuard.class);
        when(sideEffects.snapshot()).thenReturn(Map.of());
        EvaluationRunReportWriter report = mock(EvaluationRunReportWriter.class);
        when(report.write(any(), any(), anyList(), anyList(), anyMap(), any())).thenReturn(Path.of("target/report.md"));

        EvaluationRunnerService service = new EvaluationRunnerService(datasets, cases, runs, results, traceRuns,
                readiness, mock(DiagnosticDryRunService.class), mock(TraceQueryService.class),
                mock(TraceDiffService.class), List.of(), new EvaluationPromptStubRegistry(), sideEffects,
                report, new ObjectMapper(), Clock.fixed(Instant.parse("2026-06-15T00:00:00Z"), ZoneOffset.UTC));

        EvaluationRun run = service.start(1L, "mock", "stage8-default", false);

        assertThat(results.values).extracting(EvaluationCaseResult::getCaseKey)
                .containsExactly("broken_case", "following_case");
        assertThat(results.values).extracting(EvaluationCaseResult::getStatus)
                .containsExactly("ERROR", "SKIPPED");
        assertThat(run.getStatus()).isEqualTo("PARTIAL");
        assertThat(run.getErrorCount()).isEqualTo(1);
        assertThat(run.getSkippedCount()).isEqualTo(1);
    }

    private EvaluationCase evaluationCase(Long id, String key, Long sourceTraceId) {
        EvaluationCase value = new EvaluationCase();
        value.setId(id);
        value.setDatasetId(1L);
        value.setCaseKey(key);
        value.setName(key);
        value.setSourceTraceId(sourceTraceId);
        value.setExpectationJson("{}");
        return value;
    }

    private static class RunRepository implements EvaluationRunRepository {
        private EvaluationRun value;

        @Override
        public EvaluationRun save(EvaluationRun run) {
            if (run.getId() == null) run.setId(1L);
            value = run;
            return run;
        }

        @Override
        public Optional<EvaluationRun> findById(Long id) {
            return Optional.ofNullable(value);
        }
    }

    private static class ResultRepository implements EvaluationCaseResultRepository {
        private final List<EvaluationCaseResult> values = new ArrayList<>();

        @Override
        public EvaluationCaseResult save(EvaluationCaseResult result) {
            if (!values.contains(result)) values.add(result);
            return result;
        }

        @Override
        public List<EvaluationCaseResult> findResultsByRunId(Long runId) {
            return values;
        }

        @Override
        public long countByRunIdAndStatus(Long runId, String status) {
            return values.stream().filter(result -> status.equals(result.getStatus())).count();
        }
    }
}
