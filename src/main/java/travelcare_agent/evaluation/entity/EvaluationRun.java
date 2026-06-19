package travelcare_agent.evaluation.entity;
import com.baomidou.mybatisplus.annotation.*; import java.time.LocalDateTime;
@TableName("evaluation_runs") public class EvaluationRun {
 @TableId(type=IdType.ASSIGN_ID) private Long id; private Long datasetId; private Integer datasetVersion; private Long baselineRunId;
 private String providerMode; private String promptStubVersion; private String status; private Integer totalCount; private Integer passedCount;
 private Integer failedCount; private Integer errorCount; private Integer skippedCount; private String configJson; private String summaryJson;
 private String regressionStatus; private Integer regressionCount; private Integer improvedCount; private Integer newCaseCount; private Integer missingCaseCount;
 private LocalDateTime startedAt; private LocalDateTime finishedAt; private LocalDateTime createdAt;
 public Long getId(){return id;} public void setId(Long v){id=v;} public Long getDatasetId(){return datasetId;} public void setDatasetId(Long v){datasetId=v;}
 public Integer getDatasetVersion(){return datasetVersion;} public void setDatasetVersion(Integer v){datasetVersion=v;} public Long getBaselineRunId(){return baselineRunId;} public void setBaselineRunId(Long v){baselineRunId=v;}
 public String getProviderMode(){return providerMode;} public void setProviderMode(String v){providerMode=v;} public String getPromptStubVersion(){return promptStubVersion;} public void setPromptStubVersion(String v){promptStubVersion=v;}
 public String getStatus(){return status;} public void setStatus(String v){status=v;} public Integer getTotalCount(){return totalCount;} public void setTotalCount(Integer v){totalCount=v;}
 public Integer getPassedCount(){return passedCount;} public void setPassedCount(Integer v){passedCount=v;} public Integer getFailedCount(){return failedCount;} public void setFailedCount(Integer v){failedCount=v;}
 public Integer getErrorCount(){return errorCount;} public void setErrorCount(Integer v){errorCount=v;} public Integer getSkippedCount(){return skippedCount;} public void setSkippedCount(Integer v){skippedCount=v;}
 public String getConfigJson(){return configJson;} public void setConfigJson(String v){configJson=v;} public String getSummaryJson(){return summaryJson;} public void setSummaryJson(String v){summaryJson=v;}
 public String getRegressionStatus(){return regressionStatus;} public void setRegressionStatus(String v){regressionStatus=v;} public Integer getRegressionCount(){return regressionCount;} public void setRegressionCount(Integer v){regressionCount=v;}
 public Integer getImprovedCount(){return improvedCount;} public void setImprovedCount(Integer v){improvedCount=v;} public Integer getNewCaseCount(){return newCaseCount;} public void setNewCaseCount(Integer v){newCaseCount=v;} public Integer getMissingCaseCount(){return missingCaseCount;} public void setMissingCaseCount(Integer v){missingCaseCount=v;}
 public LocalDateTime getStartedAt(){return startedAt;} public void setStartedAt(LocalDateTime v){startedAt=v;} public LocalDateTime getFinishedAt(){return finishedAt;} public void setFinishedAt(LocalDateTime v){finishedAt=v;}
 public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
}
