package travelcare_agent.evaluation;

import org.junit.jupiter.api.Test;
import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.evaluation.entity.EvaluationBaseline;
import travelcare_agent.evaluation.entity.EvaluationDataset;
import travelcare_agent.evaluation.entity.EvaluationRun;
import travelcare_agent.evaluation.repository.EvaluationBaselineRepository;
import travelcare_agent.evaluation.repository.EvaluationCaseResultRepository;
import travelcare_agent.evaluation.repository.EvaluationDatasetRepository;
import travelcare_agent.evaluation.repository.EvaluationRunRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class BaselinePromotionServiceTest {
    @Test
    void promotesOnlyTrustedPassedRunAndUpdatesCurrentPointer() {
        EvaluationRun run = run("PASSED");
        EvaluationDataset dataset = dataset("ACTIVE");
        EvaluationRunRepository runs = mock(EvaluationRunRepository.class);
        EvaluationDatasetRepository datasets = mock(EvaluationDatasetRepository.class);
        EvaluationCaseResultRepository results = mock(EvaluationCaseResultRepository.class);
        EvaluationBaselineRepository baselines = mock(EvaluationBaselineRepository.class);
        when(runs.findById(10L)).thenReturn(Optional.of(run));
        when(datasets.findById(1L)).thenReturn(Optional.of(dataset));
        when(results.countByRunIdAndStatus(10L, "ERROR")).thenReturn(0L);
        when(baselines.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        EvaluationBaseline promoted = service(runs, datasets, results, baselines)
                .promote(10L, "local-dev");

        assertThat(promoted.getRunId()).isEqualTo(10L);
        assertThat(promoted.getDatasetId()).isEqualTo(1L);
        assertThat(dataset.getCurrentBaselineRunId()).isEqualTo(10L);
        verify(baselines).save(promoted);
        verify(datasets).save(dataset);
    }

    @Test
    void rejectsFailedRunAndRunContainingErrorCase() {
        EvaluationRunRepository runs = mock(EvaluationRunRepository.class);
        EvaluationDatasetRepository datasets = mock(EvaluationDatasetRepository.class);
        EvaluationCaseResultRepository results = mock(EvaluationCaseResultRepository.class);
        EvaluationBaselineRepository baselines = mock(EvaluationBaselineRepository.class);
        when(runs.findById(10L)).thenReturn(Optional.of(run("FAILED")));
        assertThatThrownBy(() -> service(runs, datasets, results, baselines).promote(10L, "dev"))
                .isInstanceOf(BusinessException.class);

        when(runs.findById(10L)).thenReturn(Optional.of(run("PASSED")));
        when(datasets.findById(1L)).thenReturn(Optional.of(dataset("ACTIVE")));
        when(results.countByRunIdAndStatus(10L, "ERROR")).thenReturn(1L);
        assertThatThrownBy(() -> service(runs, datasets, results, baselines).promote(10L, "dev"))
                .isInstanceOf(BusinessException.class);
    }

    private BaselinePromotionService service(EvaluationRunRepository runs,
            EvaluationDatasetRepository datasets, EvaluationCaseResultRepository results,
            EvaluationBaselineRepository baselines) {
        return new BaselinePromotionService(runs, datasets, results, baselines,
                Clock.fixed(Instant.parse("2026-06-15T00:00:00Z"), ZoneOffset.UTC));
    }

    private EvaluationRun run(String status) {
        EvaluationRun run = new EvaluationRun();
        run.setId(10L);
        run.setDatasetId(1L);
        run.setDatasetVersion(1);
        run.setStatus(status);
        return run;
    }

    private EvaluationDataset dataset(String status) {
        EvaluationDataset dataset = new EvaluationDataset();
        dataset.setId(1L);
        dataset.setDatasetKey("refund");
        dataset.setVersion(1);
        dataset.setStatus(status);
        return dataset;
    }
}
