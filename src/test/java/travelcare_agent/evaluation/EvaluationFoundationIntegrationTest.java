package travelcare_agent.evaluation;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import travelcare_agent.adapter.order.MockOrderAdapter;
import travelcare_agent.agent.provider.DeepSeekAgentProvider;
import travelcare_agent.dryrun.DryRunReadinessChecker;
import travelcare_agent.evaluation.entity.EvaluationCase;
import travelcare_agent.evaluation.entity.EvaluationDataset;
import travelcare_agent.evaluation.entity.EvaluationRun;
import travelcare_agent.evaluation.repository.EvaluationCaseRepository;
import travelcare_agent.evaluation.repository.EvaluationCaseResultRepository;
import travelcare_agent.trace.SpanType;
import travelcare_agent.trace.TraceEventType;
import travelcare_agent.trace.TraceQueryService;
import travelcare_agent.trace.TraceService;
import travelcare_agent.trace.TraceSnapshotType;
import travelcare_agent.trace.entity.TraceSnapshot;
import travelcare_agent.trace.repository.TraceRunRepository;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest
class EvaluationFoundationIntegrationTest {
    private static final LocalDateTime EVALUATED_AT = LocalDateTime.of(2026, 6, 15, 10, 0);

    @Autowired private EvaluationDatasetService datasets;
    @Autowired private EvaluationRunnerService runner;
    @Autowired private BaselinePromotionService baselinePromotion;
    @Autowired private EvaluationCaseRepository caseRepo;
    @Autowired private EvaluationCaseResultRepository resultRepo;
    @Autowired private TraceRunRepository traceRuns;
    @Autowired private TraceService traceService;
    @Autowired private TraceQueryService traceQueryService;
    @Autowired private DryRunReadinessChecker readinessChecker;
    @Autowired private JdbcTemplate jdbc;
    @SpyBean private MockOrderAdapter orderAdapter;
    @SpyBean private DeepSeekAgentProvider deepSeek;
    @MockBean private RabbitTemplate rabbit;

    @Test
    void runsSixIndependentRefundSourceTracesWithoutBusinessSideEffects() throws Exception {
        Map<String, Long> businessCountsBefore = counts();
        List<RefundScenario> scenarios = scenarios();
        Map<String, Long> sourceTraceIds = new LinkedHashMap<>();
        for (RefundScenario scenario : scenarios) {
            sourceTraceIds.put(scenario.caseKey(), createSourceTrace(scenario));
        }

        assertThat(sourceTraceIds.values()).doesNotHaveDuplicates().hasSize(6);
        assertSourceTraceEvidence(scenarios, sourceTraceIds);

        EvaluationDataset dataset = datasets.create(
                "refund-regression-" + System.nanoTime(), "Refund Regression", "Stage 8A");
        for (RefundScenario scenario : scenarios) {
            datasets.createCase(
                    dataset.getId(),
                    scenario.caseKey(),
                    scenario.caseKey(),
                    sourceTraceIds.get(scenario.caseKey()),
                    expectation(scenario),
                    "[\"refund\"]",
                    true
            );
        }

        List<EvaluationCase> persistedCases = caseRepo.findByDatasetId(dataset.getId());
        assertThat(persistedCases).hasSize(6);
        assertThat(persistedCases.stream().map(EvaluationCase::getSourceTraceId))
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(sourceTraceIds.values());

        datasets.activate(dataset.getId());
        reset(orderAdapter, deepSeek, rabbit);
        EvaluationRun run = runner.start(dataset.getId(), "mock", "stage8-default");
        var persistedResults = resultRepo.findResultsByRunId(run.getId());

        assertThat(run.getStatus())
                .as(persistedResults.stream()
                        .map(result -> result.getCaseKey() + ": " + result.getFailureReason()
                                + " | " + result.getScoresJson())
                        .toList().toString())
                .isEqualTo("PASSED");
        assertThat(run.getPassedCount()).isEqualTo(6);
        assertThat(persistedResults)
                .hasSize(6)
                .allMatch(result -> "PASSED".equals(result.getStatus())
                        && result.getDryRunTraceId() != null
                        && result.getDiffId() != null);
        assertThat(counts()).isEqualTo(businessCountsBefore);
        verifyNoInteractions(orderAdapter, deepSeek, rabbit);

        String report = runner.report(run.getId());
        assertThat(report).contains(
                "Provider mode: mock",
                "Passed count: 6",
                "refund_eligible_paid_over_24h",
                "refund_ineligible_used_order",
                "refund_ineligible_within_24h",
                "refund_need_human_missing_order",
                "refund_forbidden_other_user_order",
                "refund_ineligible_non_refundable"
        );
        assertThat(Files.exists(Path.of(
                "target", "evaluation", "runs", run.getId() + "_report.md"))).isTrue();
    }

    @Test
    void promotesBaselineAndDetectsControlledRegressionsWithoutBusinessSideEffects() {
        Map<String, Long> businessCountsBefore = counts();
        List<RefundScenario> scenarios = scenarios();
        EvaluationDataset dataset = datasets.create(
                "refund-baseline-" + System.nanoTime(), "Refund Baseline", "Stage 8B");
        for (RefundScenario scenario : scenarios) {
            datasets.createCase(dataset.getId(), scenario.caseKey(), scenario.caseKey(),
                    createSourceTrace(scenario), expectation(scenario), "[\"refund\"]", true);
        }
        assertThat(caseRepo.findByDatasetId(dataset.getId()).stream()
                .map(EvaluationCase::getSourceTraceId)).doesNotHaveDuplicates().hasSize(6);
        datasets.activate(dataset.getId());

        EvaluationRun baselineRun = runner.start(dataset.getId(), "mock", "stage8-default", true);
        assertThat(baselineRun.getStatus()).isEqualTo("PASSED");
        assertThat(baselineRun.getRegressionStatus()).isEqualTo("NOT_COMPARED");
        var promoted = baselinePromotion.promote(baselineRun.getId(), "stage8b-test");
        assertThat(promoted.getRunId()).isEqualTo(baselineRun.getId());
        baselinePromotion.promote(baselineRun.getId(), "stage8b-test-repeat");
        assertThat(jdbc.queryForObject("select count(*) from evaluation_baselines where dataset_id = ?",
                Long.class, dataset.getId())).isEqualTo(2L);
        assertThat(baselinePromotion.current(dataset.getId()).getRunId()).isEqualTo(baselineRun.getId());

        EvaluationRun wording = runner.start(dataset.getId(), "mock",
                "stage8-regression-policy-wording", true);
        assertThat(wording.getBaselineRunId()).isEqualTo(baselineRun.getId());
        assertThat(wording.getRegressionStatus()).isEqualTo("REGRESSION");
        assertThat(wording.getRegressionCount()).isGreaterThanOrEqualTo(1);
        assertThat(resultRepo.findResultsByRunId(wording.getId()))
                .filteredOn(r -> "REGRESSION".equals(r.getRegressionStatus()))
                .isNotEmpty()
                .allMatch(r -> r.getBaselineCaseResultId() != null
                        && r.getRegressionReasonJson().contains("changedFields")
                        && r.getRegressionReasonJson().contains("highestRisk")
                        && r.getRegressionReasonJson().contains("summary"));
        assertThat(runner.report(wording.getId()))
                .contains("Baseline Run ID: " + baselineRun.getId(), "Regression Status: REGRESSION",
                        "Regression Count", "regressionReasonJson");

        EvaluationRun unsafe = runner.start(dataset.getId(), "mock",
                "stage8-regression-unsafe", true);
        assertThat(unsafe.getRegressionStatus()).isEqualTo("REGRESSION");
        assertThat(unsafe.getRegressionCount()).isGreaterThanOrEqualTo(1);
        assertThat(counts()).isEqualTo(businessCountsBefore);
        verifyNoInteractions(orderAdapter, deepSeek, rabbit);
    }

    private Long createSourceTrace(RefundScenario scenario) {
        TraceService.RootTrace root = traceService.startRootRun(
                8000L + scenario.orderId(),
                scenario.currentUserId(),
                null,
                "mock",
                "mock-agent",
                "stage8-source",
                Map.of("caseKey", scenario.caseKey(), "source", "stage8a-fixture")
        );
        assertThat(root.available()).isTrue();

        Map<String, Object> order = order(scenario);
        Map<String, Object> policyInput = new LinkedHashMap<>();
        policyInput.put("evaluatedAt", EVALUATED_AT);
        policyInput.put("currentUserId", scenario.currentUserId());
        policyInput.put("order", order);

        Map<String, Object> policyDecision = new LinkedHashMap<>();
        policyDecision.put("decision", scenario.expectedDecision());
        policyDecision.put("reason", scenario.reason());
        policyDecision.put("refundAmount",
                "ELIGIBLE".equals(scenario.expectedDecision()) ? scenario.paidAmount() : "");
        policyDecision.put("policyResult",
                "{\"decision\":\"" + scenario.expectedDecision() + "\",\"reason\":\""
                        + scenario.reason() + "\"}");

        List<Map<String, String>> workflowSteps = List.of(
                step("COLLECTING_ORDER_REFERENCE"),
                step("QUERYING_ORDER"),
                step("CHECKING_REFUND_RULES"),
                step("RESPONDED")
        );
        Map<String, Object> workflowState = Map.of(
                "workflowType", "order_refund_inquiry",
                "status", "RESPONDED",
                "steps", workflowSteps
        );
        Map<String, Object> responseOutput = Map.of("answer", scenario.answer());

        traceService.recordSnapshot(root.traceId(), root.rootSpanId(),
                TraceSnapshotType.USER_INPUT.name(), "FIXTURE", scenario.caseKey(),
                Map.of("message", "refund " + scenario.orderNo(), "userId", scenario.currentUserId()));
        traceService.recordSnapshot(root.traceId(), root.rootSpanId(),
                TraceSnapshotType.CONTEXT_SUMMARY.name(), "FIXTURE", scenario.caseKey(),
                Map.of("caseKey", scenario.caseKey()));

        TraceService.SpanHandle retrieval = span(root, SpanType.RETRIEVAL, "source-retrieval");
        traceService.recordSnapshot(root.traceId(), retrieval.spanId(),
                TraceSnapshotType.RETRIEVAL_SUMMARY.name(), "FIXTURE", scenario.caseKey(),
                Map.of("documents", List.of(), "source", "fixture"));
        finish(retrieval);

        TraceService.SpanHandle tool = span(root, SpanType.TOOL, "source-GetOrderTool");
        traceService.recordSnapshot(root.traceId(), tool.spanId(),
                TraceSnapshotType.TOOL_REQUEST.name(), "FIXTURE", scenario.caseKey(),
                Map.of("toolName", "GetOrderTool", "orderNo", scenario.orderNo()));
        traceService.recordSnapshot(root.traceId(), tool.spanId(),
                TraceSnapshotType.TOOL_RESULT.name(), "FIXTURE", scenario.caseKey(),
                Map.of("toolName", "GetOrderTool", "status", "SUCCEEDED", "result", order));
        finish(tool);

        TraceService.SpanHandle policy = span(root, SpanType.POLICY, "refund-eligibility");
        traceService.recordSnapshot(root.traceId(), policy.spanId(),
                TraceSnapshotType.POLICY_INPUT.name(), "FIXTURE", scenario.caseKey(), policyInput);
        traceService.recordSnapshot(root.traceId(), policy.spanId(),
                TraceSnapshotType.POLICY_DECISION.name(), "FIXTURE", scenario.caseKey(), policyDecision);
        traceService.recordEvent(root.traceId(), policy.spanId(),
                TraceEventType.SUCCEEDED, "POLICY_DECISION",
                Map.of("decision", scenario.expectedDecision()));
        finish(policy);

        TraceService.SpanHandle workflow = span(root, SpanType.WORKFLOW, "source-order-refund-inquiry");
        traceService.recordSnapshot(root.traceId(), workflow.spanId(),
                TraceSnapshotType.WORKFLOW_PATH.name(), "FIXTURE", scenario.caseKey(), workflowState);
        traceService.recordSnapshot(root.traceId(), workflow.spanId(),
                "WORKFLOW_STATE", "FIXTURE", scenario.caseKey(), workflowState);
        finish(workflow);

        TraceService.SpanHandle model = span(root, SpanType.MODEL, "source-response-generation");
        traceService.recordSnapshot(root.traceId(), model.spanId(),
                TraceSnapshotType.MODEL_INPUT.name(), "MODEL_OPERATION", "RESPONSE_GENERATION",
                Map.of("operation", "RESPONSE_GENERATION", "promptVersion", "stage7b-dry-run",
                        "input", Map.of("deterministicAnswer", scenario.answer())));
        traceService.recordSnapshot(root.traceId(), model.spanId(),
                TraceSnapshotType.MODEL_OUTPUT.name(), "MODEL_OPERATION", "RESPONSE_GENERATION",
                Map.of("operation", "RESPONSE_GENERATION", "provider", "mock",
                        "model", "mock-agent", "promptVersion", "stage7b-dry-run",
                        "output", responseOutput));
        traceService.recordSnapshot(root.traceId(), model.spanId(),
                "RESPONSE_OUTPUT", "FIXTURE", scenario.caseKey(), responseOutput);
        finish(model);

        traceService.finishRootRunSuccess(
                root.traceId(), null, null, responseOutput);
        return traceRuns.findByTraceId(root.traceId()).orElseThrow().getId();
    }

    private void assertSourceTraceEvidence(
            List<RefundScenario> scenarios,
            Map<String, Long> sourceTraceIds
    ) {
        Map<String, RefundScenario> byCase = scenarios.stream()
                .collect(Collectors.toMap(RefundScenario::caseKey, Function.identity()));
        for (Map.Entry<String, Long> entry : sourceTraceIds.entrySet()) {
            RefundScenario scenario = byCase.get(entry.getKey());
            var traceRun = traceRuns.findById(entry.getValue()).orElseThrow();
            var detail = traceQueryService.get(traceRun.getTraceId());
            Map<String, TraceSnapshot> snapshots = detail.snapshots().stream()
                    .collect(Collectors.toMap(
                            TraceSnapshot::getSnapshotType,
                            Function.identity(),
                            (left, right) -> right,
                            LinkedHashMap::new
                    ));

            assertThat(readinessChecker.check(traceRun.getTraceId(), "mock").ready())
                    .as(scenario.caseKey())
                    .isTrue();
            assertThat(snapshots).containsKeys(
                    "POLICY_INPUT",
                    "POLICY_DECISION",
                    "WORKFLOW_PATH",
                    "WORKFLOW_STATE",
                    "FINAL_OUTPUT",
                    "RESPONSE_OUTPUT"
            );
            assertThat(snapshots.get("POLICY_DECISION").getPayloadJson())
                    .contains("\"decision\":\"" + scenario.expectedDecision() + "\"");
            assertThat(snapshots.get("POLICY_INPUT").getPayloadJson())
                    .contains(scenario.orderNo(), scenario.orderStatus());
            assertThat(snapshots.get("WORKFLOW_STATE").getPayloadJson())
                    .contains("\"status\":\"RESPONDED\"");
            assertThat(snapshots.get("RESPONSE_OUTPUT").getPayloadJson())
                    .contains(scenario.outputContains());
            assertThat(detail.spans().stream().map(span -> span.getSpanType()))
                    .contains("REQUEST", "RETRIEVAL", "TOOL", "POLICY", "WORKFLOW", "MODEL");
            assertThat(detail.events().stream().map(event -> event.getName()))
                    .contains("POLICY_DECISION");
        }
    }

    private String expectation(RefundScenario scenario) {
        return """
                {
                  "expectedPolicyDecision": "%s",
                  "expectedWorkflowStatus": "RESPONDED",
                  "requiredSpanTypes": ["WORKFLOW", "POLICY", "MODEL"],
                  "requiredEvents": ["POLICY_DECISION"],
                  "forbiddenEvents": ["HANDOFF_REQUIRED"],
                  "outputContains": ["%s"],
                  "maxDiffRisk": "MEDIUM",
                  "requireNoBusinessSideEffects": true
                }
                """.formatted(scenario.expectedDecision(), scenario.outputContains());
    }

    private TraceService.SpanHandle span(
            TraceService.RootTrace root,
            SpanType type,
            String name
    ) {
        return traceService.startSpan(
                root.traceId(), root.rootSpanId(), type, name, Map.of("source", "stage8a-fixture"));
    }

    private void finish(TraceService.SpanHandle span) {
        traceService.finishSpanSuccess(span, null, Map.of("source", "stage8a-fixture"));
    }

    private Map<String, Object> order(RefundScenario scenario) {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("orderId", scenario.orderId());
        order.put("orderNo", scenario.orderNo());
        order.put("userId", scenario.orderUserId());
        order.put("status", scenario.orderStatus());
        order.put("refundable", scenario.refundable());
        order.put("paidAmount", scenario.paidAmount());
        order.put("departureTime", scenario.departureTime());
        return order;
    }

    private static Map<String, String> step(String name) {
        return Map.of("name", name, "status", "SUCCEEDED", "errorCode", "");
    }

    private List<RefundScenario> scenarios() {
        return List.of(
                scenario("refund_eligible_paid_over_24h", 8101L, "ORD-8101",
                        1001L, 1001L, "PAID", true, 72,
                        "ELIGIBLE", "eligible", "eligible for refund"),
                scenario("refund_ineligible_used_order", 8102L, "ORD-8102",
                        1001L, 1001L, "USED", true, 72,
                        "INELIGIBLE", "order status is USED", "not eligible"),
                scenario("refund_ineligible_within_24h", 8103L, "ORD-8103",
                        1001L, 1001L, "PAID", true, 24,
                        "INELIGIBLE", "departure is within 24 hours", "within 24 hours"),
                scenario("refund_need_human_missing_order", 8104L, "ORD-4040",
                        -1L, 1001L, "CREATED", false, 72,
                        "NEED_HUMAN", "order ownership could not be verified",
                        "ownership could not be verified"),
                scenario("refund_forbidden_other_user_order", 8105L, "ORD-8105",
                        2002L, 1001L, "PAID", true, 72,
                        "NEED_HUMAN", "order ownership could not be verified",
                        "ownership could not be verified"),
                scenario("refund_ineligible_non_refundable", 8106L, "ORD-8106",
                        1001L, 1001L, "PAID", false, 72,
                        "INELIGIBLE", "order is marked non-refundable", "non-refundable")
        );
    }

    private RefundScenario scenario(
            String caseKey,
            Long orderId,
            String orderNo,
            Long orderUserId,
            Long currentUserId,
            String orderStatus,
            boolean refundable,
            long departureHours,
            String expectedDecision,
            String reason,
            String outputContains
    ) {
        BigDecimal paidAmount = new BigDecimal("100.0");
        String answer = "ELIGIBLE".equals(expectedDecision)
                ? "Order " + orderNo
                        + " is eligible for refund inquiry. Refund amount can be reviewed up to "
                        + paidAmount + "."
                : "Order " + orderNo
                        + " is not eligible for refund inquiry because " + reason + ".";
        return new RefundScenario(
                caseKey,
                orderId,
                orderNo,
                orderUserId,
                currentUserId,
                orderStatus,
                refundable,
                paidAmount,
                EVALUATED_AT.plusHours(departureHours),
                expectedDecision,
                reason,
                answer,
                outputContains
        );
    }

    private Map<String, Long> counts() {
        Map<String, Long> values = new LinkedHashMap<>();
        for (String table : EvaluationSideEffectGuard.BUSINESS_TABLES) {
            try {
                values.put(table,
                        jdbc.queryForObject("select count(*) from " + table, Long.class));
            } catch (Exception ignored) {
                // Some development schemas do not contain the optional async_jobs table.
            }
        }
        return values;
    }

    private record RefundScenario(
            String caseKey,
            Long orderId,
            String orderNo,
            Long orderUserId,
            Long currentUserId,
            String orderStatus,
            boolean refundable,
            BigDecimal paidAmount,
            LocalDateTime departureTime,
            String expectedDecision,
            String reason,
            String answer,
            String outputContains
    ) {
    }
}
