package travelcare_agent.dryrun;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.common.result.ResultCode;
import travelcare_agent.dryrun.entity.TraceDiff;
import travelcare_agent.trace.RedactionService;
import travelcare_agent.trace.TraceQueryService;
import travelcare_agent.trace.entity.TraceSnapshot;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TraceDiffService {
    private static final Pattern ORDER_NO = Pattern.compile("(?i)\\bORD-\\d+\\b");
    private static final Pattern REFUND_AMOUNT = Pattern.compile("(?i)(?:refund amount[^0-9]{0,40}|up to\\s+)(\\d+(?:\\.\\d+)?)");
    private static final List<String> RISK_LEVELS = List.of("NONE","LOW","MEDIUM","HIGH");
    private final TraceQueryService traceQueryService;
    private final TraceDiffPersistenceService persistence;
    private final ObjectMapper objectMapper;
    private final RedactionService redactionService;

    public TraceDiffService(TraceQueryService traceQueryService, TraceDiffPersistenceService persistence,
            ObjectMapper objectMapper, RedactionService redactionService) {
        this.traceQueryService=traceQueryService;this.persistence=persistence;this.objectMapper=objectMapper;this.redactionService=redactionService;
    }

    public TraceDiffResult create(String originalTraceId,String dryRunTraceId){
        Map<String,Object> original=summary(traceQueryService.get(originalTraceId));
        Map<String,Object> dryRun=summary(traceQueryService.get(dryRunTraceId));
        TraceDiffResult compared=compare(original,dryRun);
        TraceDiff entity=new TraceDiff();entity.setOriginalTraceId(originalTraceId);entity.setDryRunTraceId(dryRunTraceId);
        entity.setChanged(compared.changed());entity.setRiskLevel(compared.riskLevel());
        entity.setChangedFieldsJson(redacted(compared.changedFields()));entity.setOriginalSummaryJson(redacted(original));
        entity.setDryRunSummaryJson(redacted(dryRun));entity.setExplanation(redactionService.redact(compared.explanation()).value());entity.setCreatedAt(LocalDateTime.now());
        persistence.save(entity);
        return new TraceDiffResult(entity.getId(),originalTraceId,dryRunTraceId,compared.changed(),compared.riskLevel(),compared.changedFields(),original,dryRun,compared.explanation());
    }

    public TraceDiffResult get(String originalTraceId,String dryRunTraceId){
        try{TraceDiff entity=persistence.find(originalTraceId,dryRunTraceId);return new TraceDiffResult(entity.getId(),originalTraceId,dryRunTraceId,
                Boolean.TRUE.equals(entity.getChanged()),entity.getRiskLevel(),read(entity.getChangedFieldsJson(),new TypeReference<>(){}),
                read(entity.getOriginalSummaryJson(),new TypeReference<>(){}),read(entity.getDryRunSummaryJson(),new TypeReference<>(){}),entity.getExplanation());}
        catch(RuntimeException ex){throw new BusinessException(ResultCode.NOT_FOUND,"Trace diff not found");}
    }

    public static TraceDiffResult compare(Map<String,Object> original,Map<String,Object> dryRun){
        List<TraceDiffResult.ChangedField> fields=new ArrayList<>();String risk="NONE";
        boolean businessConclusionEqual=Objects.equals(businessConclusion(original),businessConclusion(dryRun));
        java.util.Set<String> keys=new java.util.LinkedHashSet<>();keys.addAll(original.keySet());keys.addAll(dryRun.keySet());
        for(String key:keys){Object left=original.get(key),right=dryRun.get(key);if(equivalent(key,left,right))continue;
            String severity=severity(key,left,right,businessConclusionEqual);fields.add(new TraceDiffResult.ChangedField(key,left,right,severity));risk=max(risk,severity);}
        String explanation=explanation(fields,risk,businessConclusionEqual);
        return new TraceDiffResult(null,null,null,!fields.isEmpty(),risk,List.copyOf(fields),original,dryRun,
                explanation);
    }

    private Map<String,Object> summary(TraceQueryService.TraceDetail detail){
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("errorCode",detail.run().getErrorCode());
        out.put("spanStatusDistribution",detail.spans().stream().collect(java.util.stream.Collectors.groupingBy(
                s->s.getSpanType()+":"+s.getStatus(),java.util.TreeMap::new,java.util.stream.Collectors.counting())));
        for(TraceSnapshot snapshot:detail.snapshots()){
            JsonNode node=parse(snapshot.getPayloadJson());
            switch(snapshot.getSnapshotType()){
                case "FINAL_OUTPUT" -> out.put("finalAnswer",node.path("answer").asText(null));
                case "WORKFLOW_PATH" -> out.put("workflowPath",node.path("steps"));
                case "TOOL_RESULT" -> out.put("toolCallSummary",node);
                case "POLICY_DECISION" -> out.put("policyDecision",node);
                case "RETRIEVAL_SUMMARY" -> out.put("retrievalSummary",node);
                case "MODEL_OUTPUT" -> out.put("modelOutputSummary",node);
                default -> { }
            }
        }
        out.put("specialEvents",detail.events().stream().filter(e->java.util.Set.of("FALLBACK","TIMEOUT","HANDOFF_REQUIRED","GUARDRAIL_BLOCKED","DRY_RUN_SKIPPED_SIDE_EFFECT").contains(e.getEventType())).map(e->e.getEventType()+":"+e.getName()).sorted().toList());
        return out;
    }

    private JsonNode parse(String json){try{return objectMapper.readTree(json);}catch(Exception ex){return objectMapper.createObjectNode();}}
    private String redacted(Object value){return redactionService.redactObject(value).value();}
    private <T>T read(String json,TypeReference<T> type){try{return objectMapper.readValue(json,type);}catch(Exception ex){throw new IllegalStateException("INVALID_TRACE_DIFF_JSON",ex);}}
    private static boolean equivalent(String field,Object left,Object right){
        if ("finalAnswer".equals(field) || "modelOutputSummary".equals(field)) {
            return Objects.equals(canonical(left), canonical(right));
        }
        return Objects.equals(normalized(field,left),normalized(field,right));
    }

    private static Object normalized(String field,Object value){
        return switch(field){
            case "policyDecision" -> policyDecision(value);
            case "toolCallSummary" -> toolBusinessResult(value);
            case "workflowPath" -> workflowPath(value);
            case "finalAnswer" -> answerConclusion(text(value));
            case "modelOutputSummary" -> answerConclusion(modelAnswer(value));
            case "specialEvents" -> stringList(value);
            case "spanStatusDistribution" -> canonical(value);
            default -> canonical(value);
        };
    }

    private static String severity(String field,Object left,Object right,boolean businessConclusionEqual){
        if("policyDecision".equals(field)||"toolCallSummary".equals(field)||"errorCode".equals(field))return "HIGH";
        if("specialEvents".equals(field))return severeEventChanged(left,right)?"HIGH":"MEDIUM";
        if("finalAnswer".equals(field))return businessConclusionEqual?"LOW":"HIGH";
        if("modelOutputSummary".equals(field))return businessConclusionEqual?"LOW":"MEDIUM";
        if(java.util.Set.of("workflowPath","retrievalSummary","spanStatusDistribution").contains(field))return "MEDIUM";
        return "LOW";
    }

    private static Map<String,Object> businessConclusion(Map<String,Object> summary){
        Map<String,Object> value=new LinkedHashMap<>();
        value.put("policy",policyDecision(summary.get("policyDecision")));
        value.put("tool",toolBusinessResult(summary.get("toolCallSummary")));
        value.put("answer",answerConclusion(text(summary.get("finalAnswer"))));
        value.put("terminalEvents",severeEvents(summary.get("specialEvents")));
        value.put("errorCode",canonical(summary.get("errorCode")));
        return value;
    }

    private static Map<String,Object> policyDecision(Object value){
        Map<String,Object> map=map(value);Map<String,Object> out=new LinkedHashMap<>();
        Object decision=map.isEmpty()?canonical(value):first(map,"decision","status");
        out.put("decision",upper(decision));
        out.put("refundAmount",number(map.get("refundAmount")));
        out.put("reason",normalizedText(text(map.get("reason"))));
        return out;
    }

    private static Map<String,Object> toolBusinessResult(Object value){
        Map<String,Object> map=map(value);Map<String,Object> result=map(map.get("result"));Map<String,Object> out=new LinkedHashMap<>();
        out.put("toolName",upper(map.get("toolName")));
        out.put("orderNo",upper(result.get("orderNo")));
        out.put("orderStatus",upper(result.get("status")));
        out.put("refundable",canonical(result.get("refundable")));
        out.put("paidAmount",number(result.get("paidAmount")));
        out.put("departureTime",canonical(result.get("departureTime")));
        return out;
    }

    private static List<Map<String,String>> workflowPath(Object value){
        Object steps=value;
        Map<String,Object> wrapper=map(value);if(wrapper.containsKey("steps"))steps=wrapper.get("steps");
        List<Map<String,String>> out=new ArrayList<>();
        for(Object item:list(steps)){Map<String,Object> step=map(item);String name=upper(first(step,"name","stepName"));
            String status=upper(step.get("status"));if("SUCCESS".equals(status))status="SUCCEEDED";
            out.add(Map.of("name",name,"status",status));}
        return out;
    }

    private static Map<String,Object> answerConclusion(String answer){
        String value=answer==null?"":answer;String lower=value.toLowerCase(java.util.Locale.ROOT);Map<String,Object> out=new LinkedHashMap<>();
        Matcher order=ORDER_NO.matcher(value);out.put("orderNo",order.find()?order.group().toUpperCase(java.util.Locale.ROOT):null);
        String conclusion=lower.contains("not eligible")||lower.contains("ineligible")?"INELIGIBLE"
                : lower.contains("eligible")?"ELIGIBLE"
                : lower.contains("manual support")||lower.contains("human review")||lower.contains("provide an order reference")?"HANDOFF":"UNKNOWN";
        out.put("conclusion",conclusion);Matcher amount=REFUND_AMOUNT.matcher(value);out.put("refundAmount",amount.find()?number(amount.group(1)):null);
        return out;
    }

    private static String modelAnswer(Object value){Map<String,Object> map=map(value);Map<String,Object> output=map(map.get("output"));return text(output.get("answer"));}
    private static boolean severeEventChanged(Object left,Object right){return !Objects.equals(severeEvents(left),severeEvents(right));}
    private static List<String> severeEvents(Object value){return stringList(value).stream().filter(event->{String upper=event.toUpperCase(java.util.Locale.ROOT);return upper.startsWith("HANDOFF_REQUIRED")||upper.startsWith("GUARDRAIL_BLOCKED")||upper.startsWith("DRY_RUN_SKIPPED_SIDE_EFFECT")||upper.startsWith("TIMEOUT");}).toList();}
    private static List<String> stringList(Object value){return list(value).stream().map(TraceDiffService::text).filter(Objects::nonNull).sorted().toList();}

    private static String explanation(List<TraceDiffResult.ChangedField> fields,String risk,boolean businessConclusionEqual){
        if(fields.isEmpty())return "Diagnostic comparison found no differences; the business conclusion is unchanged.";
        String conclusion=businessConclusionEqual?"the business conclusion is unchanged":"the business conclusion changed";
        return "Diagnostic comparison found "+fields.size()+" changed field(s); "+conclusion+", and the highest risk is "+risk+".";
    }

    private static Object canonical(Object value){
        if(value instanceof JsonNode node){if(node.isObject()){Map<String,Object> out=new java.util.TreeMap<>();node.fields().forEachRemaining(entry->out.put(entry.getKey(),canonical(entry.getValue())));return out;}
            if(node.isArray()){List<Object> out=new ArrayList<>();node.forEach(item->out.add(canonical(item)));return out;}if(node.isNumber())return number(node.asText());if(node.isBoolean())return node.asBoolean();if(node.isNull())return null;return node.asText();}
        if(value instanceof Map<?,?> map){Map<String,Object> out=new java.util.TreeMap<>();map.forEach((key,item)->out.put(String.valueOf(key),canonical(item)));return out;}
        if(value instanceof Iterable<?> iterable){List<Object> out=new ArrayList<>();iterable.forEach(item->out.add(canonical(item)));return out;}
        if(value instanceof Number)return number(value);return value;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){Object canonical=canonical(value);return canonical instanceof Map<?,?> map?(Map<String,Object>)map:Map.of();}
    private static List<Object> list(Object value){Object canonical=canonical(value);return canonical instanceof List<?> list?new ArrayList<>(list):List.of();}
    private static Object first(Map<String,Object> map,String... keys){for(String key:keys)if(map.containsKey(key))return map.get(key);return null;}
    private static String upper(Object value){String text=text(value);return text==null?null:text.toUpperCase(java.util.Locale.ROOT);}
    private static String text(Object value){if(value==null)return null;if(value instanceof JsonNode node)return node.isTextual()?node.asText():node.toString();return String.valueOf(value);}
    private static String normalizedText(String value){return value==null?null:value.trim().replaceAll("\\s+"," ").toLowerCase(java.util.Locale.ROOT);}
    private static BigDecimal number(Object value){if(value==null)return null;try{return new BigDecimal(text(value)).stripTrailingZeros();}catch(Exception ex){return null;}}
    private static String max(String a,String b){return RISK_LEVELS.indexOf(a)>=RISK_LEVELS.indexOf(b)?a:b;}
}
