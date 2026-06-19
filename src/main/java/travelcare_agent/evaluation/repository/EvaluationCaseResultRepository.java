package travelcare_agent.evaluation.repository; import travelcare_agent.evaluation.entity.EvaluationCaseResult; import java.util.*;
public interface EvaluationCaseResultRepository { EvaluationCaseResult save(EvaluationCaseResult value); List<EvaluationCaseResult> findResultsByRunId(Long runId); long countByRunIdAndStatus(Long runId,String status); }
