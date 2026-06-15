package travelcare_agent.evaluation.entity;
import com.baomidou.mybatisplus.annotation.*; import java.time.LocalDateTime;
@TableName("evaluation_datasets") public class EvaluationDataset {
 @TableId(type=IdType.ASSIGN_ID) private Long id; private String datasetKey; private String name; private Integer version;
 private String status; private String description; private Long clonedFromDatasetId; private LocalDateTime createdAt; private LocalDateTime updatedAt;
 public Long getId(){return id;} public void setId(Long v){id=v;} public String getDatasetKey(){return datasetKey;} public void setDatasetKey(String v){datasetKey=v;}
 public String getName(){return name;} public void setName(String v){name=v;} public Integer getVersion(){return version;} public void setVersion(Integer v){version=v;}
 public String getStatus(){return status;} public void setStatus(String v){status=v;} public String getDescription(){return description;} public void setDescription(String v){description=v;}
 public Long getClonedFromDatasetId(){return clonedFromDatasetId;} public void setClonedFromDatasetId(Long v){clonedFromDatasetId=v;}
 public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;} public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
}
