package travelcare_agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import travelcare_agent.evaluation.entity.EvaluationCaseResult;
import travelcare_agent.evaluation.entity.EvaluationRun;
import travelcare_agent.evaluation.repository.EvaluationCaseResultRepository;
import travelcare_agent.evaluation.repository.EvaluationRunRepository;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class BaselineComparisonServiceTest {
    @Test
    void comparesByCaseKeyAndAggregatesRunStatus() {
        RunRepo runs = new RunRepo();
        runs.values.put(1L, run(1L));
        runs.values.put(2L, run(2L));
        ResultRepo results = new ResultRepo(List.of(
                result(11L, 1L, 101L, "same", "PASSED", "LOW", true),
                result(12L, 1L, 102L, "missing", "PASSED", "LOW", true),
                result(21L, 2L, 101L, "same", "FAILED", "MEDIUM", false),
                result(22L, 2L, 103L, "new", "PASSED", "LOW", true)
        ));
        BaselineComparisonService service = new BaselineComparisonService(
                runs, results, new EvaluationCaseResultFactsExtractor(new ObjectMapper()),
                new BaselineComparisonRules(), new ObjectMapper());

        EvaluationRun current = service.compare(2L, 1L);

        assertThat(current.getRegressionStatus()).isEqualTo("PARTIAL");
        assertThat(current.getRegressionCount()).isEqualTo(1);
        assertThat(current.getNewCaseCount()).isEqualTo(1);
        assertThat(current.getMissingCaseCount()).isEqualTo(1);
        assertThat(results.findResultsByRunId(2L)).extracting(EvaluationCaseResult::getRegressionStatus)
                .containsExactlyInAnyOrder("REGRESSION", "NEW", "MISSING");
        assertThat(results.findResultsByRunId(2L)).filteredOn(r -> "REGRESSION".equals(r.getRegressionStatus()))
                .singleElement().extracting(EvaluationCaseResult::getRegressionReasonJson)
                .asString().contains("changedFields", "baseline", "current", "highestRisk", "summary");
    }

    private EvaluationRun run(Long id){EvaluationRun r=new EvaluationRun();r.setId(id);r.setDatasetId(9L);r.setDatasetVersion(1);return r;}
    private EvaluationCaseResult result(Long id,Long run,Long caseId,String key,String status,String risk,boolean output){EvaluationCaseResult r=new EvaluationCaseResult();r.setId(id);r.setRunId(run);r.setCaseId(caseId);r.setCaseKey(key);r.setSourceTraceId(caseId);r.setStatus(status);r.setRiskLevel(risk);r.setScoresJson("[{\"scorer\":\"policyDecision\",\"actual\":\"ELIGIBLE\",\"applied\":true},{\"scorer\":\"workflowOutcome\",\"actual\":\"RESPONDED\",\"applied\":true},{\"scorer\":\"outputAssertions\",\"passed\":"+output+",\"applied\":true},{\"scorer\":\"sideEffectSafety\",\"passed\":true,\"applied\":true}]");return r;}
    private static class RunRepo implements EvaluationRunRepository {Map<Long,EvaluationRun> values=new HashMap<>();public EvaluationRun save(EvaluationRun v){values.put(v.getId(),v);return v;}public Optional<EvaluationRun> findById(Long id){return Optional.ofNullable(values.get(id));}}
    private static class ResultRepo implements EvaluationCaseResultRepository {List<EvaluationCaseResult> values=new ArrayList<>();long id=100;ResultRepo(List<EvaluationCaseResult> v){values.addAll(v);}public EvaluationCaseResult save(EvaluationCaseResult v){if(v.getId()==null)v.setId(++id);if(!values.contains(v))values.add(v);return v;}public List<EvaluationCaseResult> findResultsByRunId(Long id){return values.stream().filter(v->id.equals(v.getRunId())).toList();}public long countByRunIdAndStatus(Long id,String s){return findResultsByRunId(id).stream().filter(v->s.equals(v.getStatus())).count();}}
}
