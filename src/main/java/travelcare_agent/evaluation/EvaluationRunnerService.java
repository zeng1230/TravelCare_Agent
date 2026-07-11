package travelcare_agent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.common.result.ResultCode;
import travelcare_agent.dryrun.*;
import travelcare_agent.evaluation.entity.*;
import travelcare_agent.evaluation.repository.*;
import travelcare_agent.evaluation.scoring.*;
import travelcare_agent.human.packet.HumanHandoffContextPacketBuilder;
import travelcare_agent.trace.*;
import travelcare_agent.trace.entity.*;
import travelcare_agent.trace.repository.TraceRunRepository;

import java.time.*;
import java.util.*;

@Service
public class EvaluationRunnerService {
    private final EvaluationDatasetRepository datasets;
    private final EvaluationCaseRepository cases;
    private final EvaluationRunRepository runs;
    private final EvaluationCaseResultRepository results;
    private final TraceRunRepository traceRuns;
    private final DryRunReadinessChecker readiness;
    private final DiagnosticDryRunService dryRun;
    private final TraceQueryService traces;
    private final TraceDiffService diffs;
    private final List<EvaluationScorer> scorers;
    private final EvaluationPromptStubRegistry stubs;
    private final EvaluationSideEffectGuard sideEffects;
    private final EvaluationRunReportWriter reports;
    private final BaselineComparisonService comparisons;
    private final HumanHandoffContextPacketBuilder handoffPackets;
    private final ObjectMapper json;
    private final Clock clock;

    @Autowired
    public EvaluationRunnerService(EvaluationDatasetRepository d, EvaluationCaseRepository c, EvaluationRunRepository r,
                                   EvaluationCaseResultRepository cr, TraceRunRepository tr, DryRunReadinessChecker rd, DiagnosticDryRunService dr,
                                   TraceQueryService tq, TraceDiffService df, List<EvaluationScorer> s, EvaluationPromptStubRegistry st,
                                   EvaluationSideEffectGuard se, EvaluationRunReportWriter rw, BaselineComparisonService bc,
                                   @Autowired(required = false) HumanHandoffContextPacketBuilder hp, ObjectMapper om) {
        this(d, c, r, cr, tr, rd, dr, tq, df, s, st, se, rw, bc, hp, om, Clock.systemDefaultZone());
    }

    EvaluationRunnerService(EvaluationDatasetRepository d, EvaluationCaseRepository c, EvaluationRunRepository r,
                            EvaluationCaseResultRepository cr, TraceRunRepository tr, DryRunReadinessChecker rd, DiagnosticDryRunService dr,
                            TraceQueryService tq, TraceDiffService df, List<EvaluationScorer> s, EvaluationPromptStubRegistry st,
                            EvaluationSideEffectGuard se, EvaluationRunReportWriter rw, ObjectMapper om) {
        this(d, c, r, cr, tr, rd, dr, tq, df, s, st, se, rw, null, null, om, Clock.systemDefaultZone());
    }

    EvaluationRunnerService(EvaluationDatasetRepository d, EvaluationCaseRepository c, EvaluationRunRepository r,
                            EvaluationCaseResultRepository cr, TraceRunRepository tr, DryRunReadinessChecker rd, DiagnosticDryRunService dr,
                            TraceQueryService tq, TraceDiffService df, List<EvaluationScorer> s, EvaluationPromptStubRegistry st,
                            EvaluationSideEffectGuard se, EvaluationRunReportWriter rw, ObjectMapper om, Clock clock) {
        this(d, c, r, cr, tr, rd, dr, tq, df, s, st, se, rw, null, null, om, clock);
    }

    EvaluationRunnerService(EvaluationDatasetRepository d, EvaluationCaseRepository c, EvaluationRunRepository r,
                            EvaluationCaseResultRepository cr, TraceRunRepository tr, DryRunReadinessChecker rd, DiagnosticDryRunService dr,
                            TraceQueryService tq, TraceDiffService df, List<EvaluationScorer> s, EvaluationPromptStubRegistry st,
                            EvaluationSideEffectGuard se, EvaluationRunReportWriter rw, BaselineComparisonService bc,
                            HumanHandoffContextPacketBuilder hp, ObjectMapper om, Clock clock) {
        datasets = d;
        cases = c;
        runs = r;
        results = cr;
        traceRuns = tr;
        readiness = rd;
        dryRun = dr;
        traces = tq;
        diffs = df;
        scorers = s;
        stubs = st;
        sideEffects = se;
        reports = rw;
        comparisons = bc;
        handoffPackets = hp;
        json = om;
        this.clock = clock;
    }

    EvaluationRunnerService(EvaluationDatasetRepository d, EvaluationCaseRepository c, EvaluationRunRepository r,
                            EvaluationCaseResultRepository cr, TraceRunRepository tr, DryRunReadinessChecker rd, DiagnosticDryRunService dr,
                            TraceQueryService tq, TraceDiffService df, List<EvaluationScorer> s, EvaluationPromptStubRegistry st,
                            EvaluationSideEffectGuard se, EvaluationRunReportWriter rw, BaselineComparisonService bc,
                            ObjectMapper om, Clock clock) {
        this(d, c, r, cr, tr, rd, dr, tq, df, s, st, se, rw, bc, null, om, clock);
    }

    public EvaluationRun start(Long datasetId, String provider, String stub) {
        return start(datasetId, provider, stub, true);
    }

    public EvaluationRun start(Long datasetId, String provider, String stub, boolean compareWithBaseline) {
        EvaluationDataset ds = datasets.findById(datasetId).orElseThrow(() -> new BusinessException(ResultCode.EVALUATION_DATASET_NOT_FOUND));
        if (!"ACTIVE".equals(ds.getStatus())) throw new BusinessException(ResultCode.EVALUATION_DATASET_NOT_ACTIVE);
        String p = provider == null ? "mock" : provider;
        if (!"mock".equals(p)) throw new BusinessException(ResultCode.EVALUATION_PROVIDER_NOT_ALLOWED);
        String sv = stub == null ? stubs.defaultVersion() : stub;
        if (!stubs.contains(sv)) throw new BusinessException(ResultCode.EVALUATION_PROMPT_STUB_UNKNOWN);
        List<EvaluationCase> enabled = cases.findEnabledCasesByDatasetId(datasetId);
        if (enabled.isEmpty()) throw new BusinessException(ResultCode.EVALUATION_EMPTY_DATASET);
        EvaluationRun run = new EvaluationRun();
        run.setDatasetId(datasetId);
        run.setDatasetVersion(ds.getVersion());
        run.setProviderMode("mock");
        run.setPromptStubVersion(sv);
        run.setStatus("PENDING");
        run.setTotalCount(enabled.size());
        run.setPassedCount(0);
        run.setFailedCount(0);
        run.setErrorCount(0);
        run.setSkippedCount(0);
        run.setRegressionStatus("NOT_COMPARED");
        run.setRegressionCount(0);
        run.setImprovedCount(0);
        run.setNewCaseCount(0);
        run.setMissingCaseCount(0);
        run.setConfigJson(write(Map.of("providerMode", "mock", "promptStubVersion", sv, "compareWithBaseline", compareWithBaseline)));
        run.setCreatedAt(now());
        runs.save(run);
        run.setStatus("RUNNING");
        run.setStartedAt(now());
        runs.save(run);
        Map<Long, List<ScoreResult>> allScores = new LinkedHashMap<>();
        for (EvaluationCase c : enabled) execute(run, c, allScores);
        aggregate(run);
        run.setFinishedAt(now());
        run.setSummaryJson(write(summary(run, null, null, null, compareWithBaseline && !hasBaseline(ds) ? "No baseline promoted for this dataset version" : null)));
        runs.save(run);
        String comparisonError = null;
        if (compareWithBaseline && hasBaseline(ds) && comparisons != null) {
            try {
                run = comparisons.compare(run.getId(), ds.getCurrentBaselineRunId());
            } catch (Exception ex) {
                comparisonError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
                run = runs.findById(run.getId()).orElse(run);
                run.setBaselineRunId(ds.getCurrentBaselineRunId());
                run.setRegressionStatus("PARTIAL");
                run.setSummaryJson(write(summary(run, null, null, comparisonError, null)));
                runs.save(run);
            }
        }
        String reportError = null, path = null;
        try {
            path = reports.write(run, ds, enabled, results.findResultsByRunId(run.getId()), allScores, clock).toString();
        } catch (Exception ex) {
            reportError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        }
        run.setSummaryJson(write(summary(run, path, reportError, comparisonError, compareWithBaseline && !hasBaseline(ds) ? "No baseline promoted for this dataset version" : null)));
        return runs.save(run);
    }

    private boolean hasBaseline(EvaluationDataset ds) {
        return ds.getCurrentBaselineRunId() != null;
    }

    private void execute(EvaluationRun run, EvaluationCase c, Map<Long, List<ScoreResult>> allScores) {
        EvaluationCaseResult out = base(run, c);
        Map<String, Long> before = sideEffects.snapshot();
        try {
            TraceRun source = traceRuns.findById(c.getSourceTraceId()).orElseThrow(() -> new IllegalArgumentException("source trace not found"));
            DryRunReadinessResult ready = readiness.check(source.getTraceId(), "mock");
            if (!ready.ready()) {
                out.setStatus("SKIPPED");
                out.setFailureReason("missing: " + ready.missingSnapshots());
                return;
            }
            DryRunResult dr = dryRun.runForEvaluation(source.getTraceId(), new DryRunRequest("evaluation:" + run.getId() + ":" + c.getCaseKey(), "mock", true), run.getPromptStubVersion(), (answer, decision) -> stubs.render(run.getPromptStubVersion(), answer, decision));
            if (!"SUCCEEDED".equals(dr.status())) {
                out.setStatus("ERROR");
                out.setFailureReason("dry run failed: " + dr.code());
                return;
            }
            TraceRun dry = traceRuns.findByTraceId(dr.dryRunTraceId()).orElseThrow();
            out.setDryRunTraceId(dry.getId());
            out.setDiffId(dr.diffId());
            out.setRiskLevel(dr.riskLevel());
            TraceQueryService.TraceDetail detail = traces.get(dr.dryRunTraceId());
            TraceQueryService.TraceDetail sourceDetail = traces.get(source.getTraceId());
            EvaluationScoringContext ctx = context(run, c, dr, detail, sourceDetail, source, sideEffects.compare(before));
            List<ScoreResult> scoreResults = new ArrayList<>();
            for (EvaluationScorer scorer : scorers) {
                try {
                    scoreResults.add(scorer.score(ctx));
                } catch (Exception ex) {
                    throw new ScorerFailure(scorer.name(), ex);
                }
            }
            allScores.put(c.getId(), scoreResults);
            out.setScoresJson(write(scoreResults));
            if (scoreResults.stream().noneMatch(ScoreResult::applied)) {
                out.setStatus("ERROR");
                out.setFailureReason("no applicable scorer");
            } else if (scoreResults.stream().anyMatch(x -> x.applied() && !x.passed())) {
                out.setStatus("FAILED");
                out.setFailureReason(scoreResults.stream().filter(x -> x.applied() && !x.passed()).map(ScoreResult::reason).reduce((a, b) -> a + "; " + b).orElse(null));
            } else out.setStatus("PASSED");
        } catch (ScorerFailure ex) {
            out.setStatus("ERROR");
            out.setFailureReason("scorer " + ex.scorer + ": " + ex.getCause().getClass().getSimpleName());
        } catch (Exception ex) {
            out.setStatus("ERROR");
            out.setFailureReason(ex.getClass().getSimpleName() + ": " + ex.getMessage());
        } finally {
            out.setFinishedAt(now());
            results.save(out);
        }
    }

    private EvaluationScoringContext context(EvaluationRun run, EvaluationCase c, DryRunResult dr, TraceQueryService.TraceDetail d, SideEffectCheckResult se) throws Exception {
        return context(run, c, dr, d, null, null, se);
    }

    private EvaluationScoringContext context(EvaluationRun run, EvaluationCase c, DryRunResult dr,
            TraceQueryService.TraceDetail d, TraceQueryService.TraceDetail sourceDetail, TraceRun sourceRun,
            SideEffectCheckResult se) throws Exception {
        EvaluationScoringContext x = new EvaluationScoringContext();
        x.run = run;
        x.evaluationCase = c;
        x.expectation = json.readTree(c.getExpectationJson());
        x.sourceTraceId = c.getSourceTraceId();
        x.dryRunTraceId = traceRuns.findByTraceId(dr.dryRunTraceId()).map(TraceRun::getId).orElse(null);
        x.diffId = dr.diffId();
        x.dryRunResult = dr;
        x.sideEffectCheckResult = se;
        x.promptStubVersion = run.getPromptStubVersion();
        x.providerMode = run.getProviderMode();
        x.clock = clock;
        x.riskLevel = dr.riskLevel();
        x.spanTypes = d.spans().stream().map(TraceSpan::getSpanType).distinct().toList();
        List<String> names = new ArrayList<>();
        d.events().forEach(e -> {
            names.add(e.getEventType());
            names.add(e.getName());
        });
        d.snapshots().forEach(s -> names.add(s.getSnapshotType()));
        x.eventNames = names;
        x.policyDecision = snapshotText(d, "POLICY_DECISION", "decision");
        x.workflowStatus = snapshotText(d, "WORKFLOW_PATH", "status");
        x.output = snapshotText(d, "FINAL_OUTPUT", "answer");
        applyStage9Context(x, d);
        if (sourceDetail != null) applyPr3cContext(x, sourceDetail, snapshotJson(sourceDetail, "FINAL_OUTPUT"));
        buildHandoffPacket(x, sourceRun);
        return x;
    }

    private void applyStage9Context(EvaluationScoringContext x, TraceQueryService.TraceDetail d) throws Exception {
        JsonNode answerability = snapshotJson(d, "ANSWERABILITY_DECISION"), citation = snapshotJson(d, "CITATION_SUMMARY"), finalOutput = snapshotJson(d, "FINAL_OUTPUT");
        x.answerabilityDecisionSnapshot = answerability;
        x.citationSummarySnapshot = citation;
        if (answerability != null) {
            x.answerabilityStatus = text(answerability, "status");
            x.answerabilityReasonCode = text(answerability, "reasonCode");
            x.requiredAction = text(answerability, "requiredAction");
            x.businessDecisionLocked = bool(answerability, "businessDecisionLocked");
            x.ragMayExplainBusinessDecision = bool(answerability, "ragMayExplainBusinessDecision");
            x.ragMayOverrideBusinessDecision = bool(answerability, "ragMayOverrideBusinessDecision");
        }
        if (citation != null) {
            x.citations = citation.path("citations");
            x.rejectedCitationCandidates = citation.path("rejectedCitationCandidates");
        }
        if (finalOutput != null && finalOutput.has("fallbackUsed"))
            x.fallbackUsed = finalOutput.path("fallbackUsed").asBoolean(false);
        applyPr3cContext(x, d, finalOutput);
    }

    private void applyPr3cContext(EvaluationScoringContext x, TraceQueryService.TraceDetail d, JsonNode finalOutput) throws Exception {
        JsonNode safety = snapshotJson(d, "MODEL_SAFETY_DECISION");
        if (safety != null) {
            x.safetyDecision = text(safety, "safetyDecision");
            x.safetyReasonCode = text(safety, "reasonCode");
            x.safetyRiskFlags = riskFlags(safety.path("riskFlags"));
        }
        List<String> supplierFailures = d.spans().stream()
                .map(TraceSpan::getErrorCode)
                .filter(code -> code != null && code.startsWith("SUPPLIER_"))
                .toList();
        if (x.supplierFailureCode == null) {
            x.supplierFailureCode = supplierFailures.stream().findFirst().orElse(null);
        }
        boolean participated = d.spans().stream()
                .anyMatch(span -> "TOOL".equals(span.getSpanType()) && "GetOrderTool".equals(span.getName()))
                || x.supplierFailureCode != null;
        x.supplierGatewayParticipated = Boolean.TRUE.equals(x.supplierGatewayParticipated) || participated;
        boolean fallbackSignal = d.events().stream().anyMatch(event -> "FALLBACK".equals(event.getEventType()))
                || d.spans().stream().anyMatch(span -> "FALLBACK".equals(span.getSpanType()));
        x.providerFallbackUsed = Boolean.TRUE.equals(x.providerFallbackUsed) || fallbackSignal;
        List<String> diagnostics = new ArrayList<>();
        d.snapshots().forEach(snapshot -> diagnostics.add(snapshot.getSnapshotType()));
        d.spans().stream().map(TraceSpan::getErrorCode).filter(Objects::nonNull).forEach(diagnostics::add);
        d.events().stream().map(TraceEvent::getEventType).filter(Objects::nonNull).forEach(diagnostics::add);
        x.leakageCheckText = String.join("\n", diagnostics);
    }

    private void buildHandoffPacket(EvaluationScoringContext x, TraceRun sourceRun) {
        if (handoffPackets == null || sourceRun == null || sourceRun.getWorkflowId() == null) return;
        try {
            x.handoffPacket = handoffPackets.build(new HumanHandoffContextPacketBuilder.Request(
                    sourceRun.getSessionId(), sourceRun.getWorkflowId(), null, "REFUND_REVIEW", "HIGH",
                    x.supplierFailureCode == null ? "NEED_HUMAN" : x.supplierFailureCode, "{}"));
            x.approvalAllowed = x.handoffPacket.refundRuleDecision() != null
                    && x.handoffPacket.refundRuleDecision().evidenceSufficientForManualDecision();
        } catch (RuntimeException ex) {
            x.handoffPacket = null;
            x.approvalAllowed = false;
        }
    }

    private static List<String> riskFlags(JsonNode flags) {
        if (flags == null || !flags.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonNode flag : flags) {
            if (flag.isTextual()) values.add(flag.asText());
            else {
                JsonNode code = flag.get("code");
                if (code != null && !code.isNull()) values.add(code.asText());
            }
        }
        return values;
    }

    private String snapshotText(TraceQueryService.TraceDetail d, String type, String field) {
        return d.snapshots().stream().filter(s -> type.equals(s.getSnapshotType())).reduce((a, b) -> b).map(s -> {
            try {
                JsonNode n = json.readTree(s.getPayloadJson());
                return n.path(field).asText(null);
            } catch (Exception e) {
                return null;
            }
        }).orElse(null);
    }

    private JsonNode snapshotJson(TraceQueryService.TraceDetail d, String type) throws Exception {
        return d.snapshots().stream().filter(s -> type.equals(s.getSnapshotType())).reduce((a, b) -> b).map(s -> {
            try {
                return json.readTree(s.getPayloadJson());
            } catch (Exception e) {
                return null;
            }
        }).orElse(null);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static Boolean bool(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asBoolean(false);
    }

    private EvaluationCaseResult base(EvaluationRun r, EvaluationCase c) {
        EvaluationCaseResult v = new EvaluationCaseResult();
        v.setRunId(r.getId());
        v.setCaseId(c.getId());
        v.setCaseKey(c.getCaseKey());
        v.setSourceTraceId(c.getSourceTraceId());
        v.setRegressionStatus("NOT_COMPARED");
        v.setStartedAt(now());
        return v;
    }

    private void aggregate(EvaluationRun r) {
        int p = (int) results.countByRunIdAndStatus(r.getId(), "PASSED"), f = (int) results.countByRunIdAndStatus(r.getId(), "FAILED"), e = (int) results.countByRunIdAndStatus(r.getId(), "ERROR"), s = (int) results.countByRunIdAndStatus(r.getId(), "SKIPPED");
        r.setPassedCount(p);
        r.setFailedCount(f);
        r.setErrorCount(e);
        r.setSkippedCount(s);
        r.setStatus(e > 0 ? "PARTIAL" : f > 0 ? "FAILED" : s > 0 ? "PARTIAL" : p > 0 ? "PASSED" : "PARTIAL");
    }

    private Map<String, Object> summary(EvaluationRun r, String path, String reportError, String comparisonError, String regressionReason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalCount", r.getTotalCount());
        m.put("passedCount", r.getPassedCount());
        m.put("failedCount", r.getFailedCount());
        m.put("errorCount", r.getErrorCount());
        m.put("skippedCount", r.getSkippedCount());
        m.put("regressionStatus", r.getRegressionStatus());
        m.put("regressionCount", r.getRegressionCount());
        m.put("improvedCount", r.getImprovedCount());
        m.put("newCaseCount", r.getNewCaseCount());
        m.put("missingCaseCount", r.getMissingCaseCount());
        if (path != null) m.put("reportPath", path);
        if (reportError != null) m.put("reportError", reportError);
        if (comparisonError != null) m.put("comparisonError", comparisonError);
        if (regressionReason != null) m.put("regressionReason", regressionReason);
        return m;
    }

    private String write(Object v) {
        try {
            return json.writeValueAsString(v);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static class ScorerFailure extends RuntimeException {
        final String scorer;

        ScorerFailure(String s, Throwable t) {
            super(t);
            scorer = s;
        }
    }

    public EvaluationRun get(Long id) {
        return runs.findById(id).orElseThrow(() -> new BusinessException(ResultCode.EVALUATION_RUN_NOT_FOUND));
    }

    public List<EvaluationCaseResult> results(Long id) {
        get(id);
        return results.findResultsByRunId(id);
    }

    public String report(Long id) {
        get(id);
        try {
            return reports.read(id);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.EVALUATION_REPORT_NOT_FOUND);
        }
    }
}
