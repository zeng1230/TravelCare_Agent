package travelcare_agent.evaluation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("evaluation_baselines")
public class EvaluationBaseline {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long datasetId;
    private String datasetKey;
    private Integer datasetVersion;
    private Long runId;
    private String promotedBy;
    private LocalDateTime promotedAt;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long v) {
        id = v;
    }

    public Long getDatasetId() {
        return datasetId;
    }

    public void setDatasetId(Long v) {
        datasetId = v;
    }

    public String getDatasetKey() {
        return datasetKey;
    }

    public void setDatasetKey(String v) {
        datasetKey = v;
    }

    public Integer getDatasetVersion() {
        return datasetVersion;
    }

    public void setDatasetVersion(Integer v) {
        datasetVersion = v;
    }

    public Long getRunId() {
        return runId;
    }

    public void setRunId(Long v) {
        runId = v;
    }

    public String getPromotedBy() {
        return promotedBy;
    }

    public void setPromotedBy(String v) {
        promotedBy = v;
    }

    public LocalDateTime getPromotedAt() {
        return promotedAt;
    }

    public void setPromotedAt(LocalDateTime v) {
        promotedAt = v;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime v) {
        createdAt = v;
    }
}
