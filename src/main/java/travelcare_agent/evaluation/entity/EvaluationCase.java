package travelcare_agent.evaluation.entity;
import com.baomidou.mybatisplus.annotation.*; import java.time.LocalDateTime;
@TableName("evaluation_cases") public class EvaluationCase {
 @TableId(type=IdType.ASSIGN_ID) private Long id; private Long datasetId; private String caseKey; private String name; private Long sourceTraceId;
 private String expectationJson; private String tagsJson; private Boolean enabled; private LocalDateTime createdAt; private LocalDateTime updatedAt;
 public Long getId(){return id;} public void setId(Long v){id=v;} public Long getDatasetId(){return datasetId;} public void setDatasetId(Long v){datasetId=v;}
 public String getCaseKey(){return caseKey;} public void setCaseKey(String v){caseKey=v;} public String getName(){return name;} public void setName(String v){name=v;}
 public Long getSourceTraceId(){return sourceTraceId;} public void setSourceTraceId(Long v){sourceTraceId=v;} public String getExpectationJson(){return expectationJson;} public void setExpectationJson(String v){expectationJson=v;}
 public String getTagsJson(){return tagsJson;} public void setTagsJson(String v){tagsJson=v;} public Boolean getEnabled(){return enabled;} public void setEnabled(Boolean v){enabled=v;}
 public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;} public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
}
