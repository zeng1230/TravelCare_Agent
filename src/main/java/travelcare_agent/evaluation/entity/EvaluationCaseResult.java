package travelcare_agent.evaluation.entity;
import com.baomidou.mybatisplus.annotation.*; import java.time.LocalDateTime;
@TableName("evaluation_case_results") public class EvaluationCaseResult {
 @TableId(type=IdType.ASSIGN_ID) private Long id; private Long runId; private Long caseId; private String caseKey; private Long sourceTraceId;
 private Long dryRunTraceId; private Long diffId; private String status; private String scoresJson; private String failureReason; private String riskLevel;
 private Long baselineCaseResultId; private String regressionStatus; private String regressionReasonJson;
 private LocalDateTime startedAt; private LocalDateTime finishedAt;
 public Long getId(){return id;} public void setId(Long v){id=v;} public Long getRunId(){return runId;} public void setRunId(Long v){runId=v;} public Long getCaseId(){return caseId;} public void setCaseId(Long v){caseId=v;}
 public String getCaseKey(){return caseKey;} public void setCaseKey(String v){caseKey=v;} public Long getSourceTraceId(){return sourceTraceId;} public void setSourceTraceId(Long v){sourceTraceId=v;}
 public Long getDryRunTraceId(){return dryRunTraceId;} public void setDryRunTraceId(Long v){dryRunTraceId=v;} public Long getDiffId(){return diffId;} public void setDiffId(Long v){diffId=v;}
 public String getStatus(){return status;} public void setStatus(String v){status=v;} public String getScoresJson(){return scoresJson;} public void setScoresJson(String v){scoresJson=v;}
 public String getFailureReason(){return failureReason;} public void setFailureReason(String v){failureReason=v;} public String getRiskLevel(){return riskLevel;} public void setRiskLevel(String v){riskLevel=v;}
 public Long getBaselineCaseResultId(){return baselineCaseResultId;} public void setBaselineCaseResultId(Long v){baselineCaseResultId=v;} public String getRegressionStatus(){return regressionStatus;} public void setRegressionStatus(String v){regressionStatus=v;} public String getRegressionReasonJson(){return regressionReasonJson;} public void setRegressionReasonJson(String v){regressionReasonJson=v;}
 public LocalDateTime getStartedAt(){return startedAt;} public void setStartedAt(LocalDateTime v){startedAt=v;} public LocalDateTime getFinishedAt(){return finishedAt;} public void setFinishedAt(LocalDateTime v){finishedAt=v;}
}
