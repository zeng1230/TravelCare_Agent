package travelcare_agent.trace.entity;

import com.baomidou.mybatisplus.annotation.IdType; import com.baomidou.mybatisplus.annotation.TableId; import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
@TableName("agent_trace_spans")
public class TraceSpan {
 @TableId(type=IdType.ASSIGN_ID) private Long id; private String spanId; private String traceId; private String parentSpanId;
 private String spanType; private String name; private String status; private LocalDateTime startedAt; private LocalDateTime finishedAt;
 private Long durationMs; private String inputRef; private String outputRef; private String errorCode; private String errorMessage; private String metadataJson;
 public Long getId(){return id;} public void setId(Long v){id=v;} public String getSpanId(){return spanId;} public void setSpanId(String v){spanId=v;}
 public String getTraceId(){return traceId;} public void setTraceId(String v){traceId=v;} public String getParentSpanId(){return parentSpanId;} public void setParentSpanId(String v){parentSpanId=v;}
 public String getSpanType(){return spanType;} public void setSpanType(String v){spanType=v;} public String getName(){return name;} public void setName(String v){name=v;}
 public String getStatus(){return status;} public void setStatus(String v){status=v;} public LocalDateTime getStartedAt(){return startedAt;} public void setStartedAt(LocalDateTime v){startedAt=v;}
 public LocalDateTime getFinishedAt(){return finishedAt;} public void setFinishedAt(LocalDateTime v){finishedAt=v;} public Long getDurationMs(){return durationMs;} public void setDurationMs(Long v){durationMs=v;}
 public String getInputRef(){return inputRef;} public void setInputRef(String v){inputRef=v;} public String getOutputRef(){return outputRef;} public void setOutputRef(String v){outputRef=v;}
 public String getErrorCode(){return errorCode;} public void setErrorCode(String v){errorCode=v;} public String getErrorMessage(){return errorMessage;} public void setErrorMessage(String v){errorMessage=v;}
 public String getMetadataJson(){return metadataJson;} public void setMetadataJson(String v){metadataJson=v;}
}
