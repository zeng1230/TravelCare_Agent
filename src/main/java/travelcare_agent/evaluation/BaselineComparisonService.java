package travelcare_agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.common.result.ResultCode;
import travelcare_agent.evaluation.entity.EvaluationCaseResult;
import travelcare_agent.evaluation.entity.EvaluationRun;
import travelcare_agent.evaluation.repository.EvaluationCaseResultRepository;
import travelcare_agent.evaluation.repository.EvaluationRunRepository;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class BaselineComparisonService {
    private final EvaluationRunRepository runs;
    private final EvaluationCaseResultRepository results;
    private final EvaluationCaseResultFactsExtractor extractor;
    private final BaselineComparisonRules rules;
    private final ObjectMapper json;

    public BaselineComparisonService(EvaluationRunRepository runs, EvaluationCaseResultRepository results, EvaluationCaseResultFactsExtractor extractor, BaselineComparisonRules rules, ObjectMapper json) {
        this.runs = runs;
        this.results = results;
        this.extractor = extractor;
        this.rules = rules;
        this.json = json;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EvaluationRun compare(Long runId, Long baselineRunId) {
        EvaluationRun currentRun = run(runId), baselineRun = run(baselineRunId);
        if (!Objects.equals(currentRun.getDatasetId(), baselineRun.getDatasetId()) || !Objects.equals(currentRun.getDatasetVersion(), baselineRun.getDatasetVersion()))
            throw new IllegalArgumentException("baseline dataset version mismatch");
        Map<String, EvaluationCaseResult> baseline = map(results.findResultsByRunId(baselineRunId));
        Map<String, EvaluationCaseResult> current = map(results.findResultsByRunId(runId));
        int regressions = 0, improved = 0, newCases = 0, missing = 0;
        boolean partial = false;
        for (EvaluationCaseResult value : current.values()) {
            EvaluationCaseResult base = baseline.remove(value.getCaseKey());
            if (base == null) {
                value.setRegressionStatus(RegressionCaseStatus.NEW.name());
                value.setRegressionReasonJson(reason(null, extractor.extract(value), List.of(), value.getRiskLevel(), "Case is new in current run", "NEW_CASE"));
                newCases++;
            } else {
                BaselineComparisonDecision d = rules.compare(extractor.extract(base), extractor.extract(value));
                value.setBaselineCaseResultId(base.getId());
                value.setRegressionStatus(d.status().name());
                value.setRegressionReasonJson(reason(extractor.extract(base), extractor.extract(value), d.changedFields(), d.highestRisk(), d.summary(), d.status().name()));
                if (d.status() == RegressionCaseStatus.REGRESSION) regressions++;
                if (d.status() == RegressionCaseStatus.IMPROVED) improved++;
                if (d.partial()) partial = true;
            }
            results.save(value);
        }
        for (EvaluationCaseResult base : baseline.values()) {
            EvaluationCaseResult synthetic = new EvaluationCaseResult();
            synthetic.setRunId(runId);
            synthetic.setCaseId(base.getCaseId());
            synthetic.setCaseKey(base.getCaseKey());
            synthetic.setSourceTraceId(base.getSourceTraceId());
            synthetic.setStatus("SKIPPED");
            synthetic.setFailureReason("missing in current run");
            synthetic.setBaselineCaseResultId(base.getId());
            synthetic.setRegressionStatus(RegressionCaseStatus.MISSING.name());
            synthetic.setRegressionReasonJson(reason(extractor.extract(base), null, List.of("casePresence"), base.getRiskLevel(), "Case is missing in current run", "MISSING_CASE"));
            synthetic.setStartedAt(LocalDateTime.now());
            synthetic.setFinishedAt(LocalDateTime.now());
            results.save(synthetic);
            missing++;
            partial = true;
        }
        currentRun.setBaselineRunId(baselineRunId);
        currentRun.setRegressionCount(regressions);
        currentRun.setImprovedCount(improved);
        currentRun.setNewCaseCount(newCases);
        currentRun.setMissingCaseCount(missing);
        currentRun.setRegressionStatus(partial ? RegressionRunStatus.PARTIAL.name() : regressions > 0 ? RegressionRunStatus.REGRESSION.name() : RegressionRunStatus.PASS.name());
        return runs.save(currentRun);
    }

    private EvaluationRun run(Long id) {
        return runs.findById(id).orElseThrow(() -> new BusinessException(ResultCode.EVALUATION_RUN_NOT_FOUND));
    }

    private Map<String, EvaluationCaseResult> map(List<EvaluationCaseResult> values) {
        Map<String, EvaluationCaseResult> out = new LinkedHashMap<>();
        for (EvaluationCaseResult v : values) out.put(v.getCaseKey(), v);
        return out;
    }

    private String reason(EvaluationCaseResultFacts baseline, EvaluationCaseResultFacts current, List<String> changed, String risk, String summary, String type) {
        try {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("type", type);
            v.put("changedFields", changed);
            v.put("baseline", baseline);
            v.put("current", current);
            v.put("highestRisk", risk == null ? "UNKNOWN" : risk);
            v.put("summary", summary);
            return json.writeValueAsString(v);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
