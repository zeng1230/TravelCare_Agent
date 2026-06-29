package travelcare_agent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import travelcare_agent.common.result.Result;
import travelcare_agent.common.result.ResultCode;
import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.evaluation.*;
import travelcare_agent.evaluation.dto.*;

import java.util.List;

@RestController
@RequestMapping("/api/evaluation")
@PreAuthorize("hasAnyRole('EVALUATOR','ADMIN')")
public class EvaluationController {
    private final EvaluationDatasetService datasets;
    private final EvaluationRunnerService runs;
    private final BaselinePromotionService baselines;
    private final ObjectMapper json;

    public EvaluationController(EvaluationDatasetService d, EvaluationRunnerService r, BaselinePromotionService b, ObjectMapper j) {
        datasets = d;
        runs = r;
        baselines = b;
        json = j;
    }

    @PostMapping("/datasets")
    public Result<EvaluationDatasetResponse> create(@RequestBody CreateEvaluationDatasetRequest r) {
        return Result.success(EvaluationDatasetResponse.from(datasets.create(r.datasetKey(), r.name(), r.description())));
    }

    @GetMapping("/datasets/{id}")
    public Result<EvaluationDatasetResponse> get(@PathVariable Long id) {
        return Result.success(EvaluationDatasetResponse.from(datasets.get(id)));
    }

    @PostMapping("/datasets/{id}/activate")
    public Result<EvaluationDatasetResponse> activate(@PathVariable Long id) {
        return Result.success(EvaluationDatasetResponse.from(datasets.activate(id)));
    }

    @PostMapping("/datasets/{id}/versions")
    public Result<EvaluationDatasetResponse> clone(@PathVariable Long id) {
        return Result.success(EvaluationDatasetResponse.from(datasets.cloneVersion(id)));
    }

    @PostMapping("/datasets/{id}/cases")
    public Result<EvaluationCaseResponse> createCase(@PathVariable Long id, @RequestBody CreateEvaluationCaseRequest r) {
        return Result.success(EvaluationCaseResponse.from(datasets.createCase(id, r.caseKey(), r.name(), r.sourceTraceId(), text(r.expectationJson()), text(r.tagsJson()), !Boolean.FALSE.equals(r.enabled()))));
    }

    @PutMapping("/datasets/{id}/cases/{caseId}")
    public Result<EvaluationCaseResponse> updateCase(@PathVariable Long id, @PathVariable Long caseId, @RequestBody UpdateEvaluationCaseRequest r) {
        return Result.success(EvaluationCaseResponse.from(datasets.updateCase(id, caseId, r.caseKey(), r.name(), r.sourceTraceId(), text(r.expectationJson()), text(r.tagsJson()), !Boolean.FALSE.equals(r.enabled()))));
    }

    @DeleteMapping("/datasets/{id}/cases/{caseId}")
    public Result<Void> deleteCase(@PathVariable Long id, @PathVariable Long caseId) {
        datasets.deleteCase(id, caseId);
        return Result.success();
    }

    @PostMapping("/datasets/{id}/runs")
    public Result<EvaluationRunResponse> start(@PathVariable Long id, @RequestBody(required = false) StartEvaluationRunRequest r) {
        if (r != null && ((r.promptText() != null && !r.promptText().isBlank()) || (r.apiKey() != null && !r.apiKey().isBlank())))
            throw new BusinessException(ResultCode.EVALUATION_INVALID_EXPECTATION, "Prompt text and API key are not allowed");
        return Result.success(EvaluationRunResponse.from(runs.start(id, r == null ? null : r.providerMode(), r == null ? null : r.promptStubVersion())));
    }

    @PostMapping("/runs/{id}/promote-baseline")
    public Result<EvaluationBaselineResponse> promoteBaseline(@PathVariable Long id, @RequestBody(required = false) PromoteBaselineRequest r) {
        return Result.success(EvaluationBaselineResponse.from(baselines.promote(id, r == null ? null : r.promotedBy())));
    }

    @GetMapping("/datasets/{id}/baseline")
    public Result<EvaluationBaselineResponse> baseline(@PathVariable Long id) {
        return Result.success(EvaluationBaselineResponse.from(baselines.current(id)));
    }

    @GetMapping("/runs/{id}")
    public Result<EvaluationRunResponse> run(@PathVariable Long id) {
        return Result.success(EvaluationRunResponse.from(runs.get(id)));
    }

    @GetMapping("/runs/{id}/results")
    public Result<List<EvaluationCaseResultResponse>> results(@PathVariable Long id) {
        return Result.success(runs.results(id).stream().map(EvaluationCaseResultResponse::from).toList());
    }

    @GetMapping("/runs/{id}/report")
    public Result<EvaluationRunReportResponse> report(@PathVariable Long id) {
        return Result.success(new EvaluationRunReportResponse(id, runs.report(id)));
    }

    private String text(Object v) {
        try {
            return v == null ? null : json.writeValueAsString(v);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.EVALUATION_INVALID_EXPECTATION);
        }
    }
}
