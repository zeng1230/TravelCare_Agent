package travelcare_agent.dryrun.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("agent_trace_diffs")
public class TraceDiff {
    @TableId(type = IdType.ASSIGN_ID) private Long id;
    private String originalTraceId;
    private String dryRunTraceId;
    private Boolean changed;
    private String riskLevel;
    private String changedFieldsJson;
    private String originalSummaryJson;
    private String dryRunSummaryJson;
    private String explanation;
    private LocalDateTime createdAt;
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public String getOriginalTraceId(){return originalTraceId;} public void setOriginalTraceId(String v){originalTraceId=v;}
    public String getDryRunTraceId(){return dryRunTraceId;} public void setDryRunTraceId(String v){dryRunTraceId=v;}
    public Boolean getChanged(){return changed;} public void setChanged(Boolean v){changed=v;}
    public String getRiskLevel(){return riskLevel;} public void setRiskLevel(String v){riskLevel=v;}
    public String getChangedFieldsJson(){return changedFieldsJson;} public void setChangedFieldsJson(String v){changedFieldsJson=v;}
    public String getOriginalSummaryJson(){return originalSummaryJson;} public void setOriginalSummaryJson(String v){originalSummaryJson=v;}
    public String getDryRunSummaryJson(){return dryRunSummaryJson;} public void setDryRunSummaryJson(String v){dryRunSummaryJson=v;}
    public String getExplanation(){return explanation;} public void setExplanation(String v){explanation=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
}
