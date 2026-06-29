package travelcare_agent.evaluation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.common.result.ResultCode;
import travelcare_agent.evaluation.entity.*;
import travelcare_agent.evaluation.repository.*;

import java.time.Clock;
import java.time.LocalDateTime;

@Service
public class BaselinePromotionService {
    private final EvaluationRunRepository runs;
    private final EvaluationDatasetRepository datasets;
    private final EvaluationCaseResultRepository results;
    private final EvaluationBaselineRepository baselines;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public BaselinePromotionService(EvaluationRunRepository r, EvaluationDatasetRepository d, EvaluationCaseResultRepository cr, EvaluationBaselineRepository b) {
        this(r, d, cr, b, Clock.systemDefaultZone());
    }

    BaselinePromotionService(EvaluationRunRepository r, EvaluationDatasetRepository d, EvaluationCaseResultRepository cr, EvaluationBaselineRepository b, Clock c) {
        runs = r;
        datasets = d;
        results = cr;
        baselines = b;
        clock = c;
    }

    @Transactional
    public EvaluationBaseline promote(Long runId, String promotedBy) {
        if (promotedBy == null || promotedBy.isBlank() || promotedBy.length() > 128)
            throw new BusinessException(ResultCode.EVALUATION_BASELINE_PROMOTION_NOT_ALLOWED, "promotedBy is required");
        EvaluationRun run = runs.findById(runId).orElseThrow(() -> new BusinessException(ResultCode.EVALUATION_RUN_NOT_FOUND));
        if (!"PASSED".equals(run.getStatus()))
            throw new BusinessException(ResultCode.EVALUATION_BASELINE_PROMOTION_NOT_ALLOWED, "Only PASSED runs can be promoted");
        if (!Integer.valueOf(0).equals(run.getFailedCount()) || !Integer.valueOf(0).equals(run.getErrorCount()) || !Integer.valueOf(0).equals(run.getSkippedCount()))
            throw new BusinessException(ResultCode.EVALUATION_BASELINE_PROMOTION_NOT_ALLOWED, "Run must have zero failed, error, and skipped cases");
        EvaluationDataset dataset = datasets.findById(run.getDatasetId()).orElseThrow(() -> new BusinessException(ResultCode.EVALUATION_DATASET_NOT_FOUND));
        if (!"ACTIVE".equals(dataset.getStatus()) || results.countByRunIdAndStatus(runId, "ERROR") > 0)
            throw new BusinessException(ResultCode.EVALUATION_BASELINE_PROMOTION_NOT_ALLOWED);
        LocalDateTime now = LocalDateTime.now(clock);
        EvaluationBaseline baseline = new EvaluationBaseline();
        baseline.setDatasetId(dataset.getId());
        baseline.setDatasetKey(dataset.getDatasetKey());
        baseline.setDatasetVersion(dataset.getVersion());
        baseline.setRunId(runId);
        baseline.setPromotedBy(promotedBy.trim());
        baseline.setPromotedAt(now);
        baseline.setCreatedAt(now);
        baselines.save(baseline);
        dataset.setCurrentBaselineRunId(runId);
        dataset.setUpdatedAt(now);
        datasets.save(dataset);
        return baseline;
    }

    public EvaluationBaseline current(Long datasetId) {
        EvaluationDataset d = datasets.findById(datasetId).orElseThrow(() -> new BusinessException(ResultCode.EVALUATION_DATASET_NOT_FOUND));
        if (d.getCurrentBaselineRunId() == null) return null;
        return baselines.findCurrent(datasetId, d.getCurrentBaselineRunId()).orElse(null);
    }
}
