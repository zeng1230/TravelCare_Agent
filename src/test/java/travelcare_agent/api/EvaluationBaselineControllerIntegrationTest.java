package travelcare_agent.api;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import travelcare_agent.evaluation.entity.EvaluationDataset;
import travelcare_agent.evaluation.entity.EvaluationRun;
import travelcare_agent.evaluation.repository.EvaluationBaselineRepository;
import travelcare_agent.evaluation.repository.EvaluationDatasetRepository;
import travelcare_agent.evaluation.repository.EvaluationRunRepository;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EvaluationBaselineControllerIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private EvaluationDatasetRepository datasets;
    @Autowired private EvaluationRunRepository runs;
    @Autowired private EvaluationBaselineRepository baselines;
    @MockBean private RabbitTemplate rabbitTemplate;

    @Test
    void promotesPassedRunAndReturnsCurrentBaselineThroughHttpEndpoints() throws Exception {
        EvaluationDataset dataset = dataset();
        EvaluationRun run = run(dataset, "PASSED", 0, 0, 0);

        mvc.perform(post("/api/evaluation/runs/{id}/promote-baseline", run.getId())
                        .contentType("application/json")
                        .content("{\"promotedBy\":\"manual-acceptance\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.baselineId").isNumber())
                .andExpect(jsonPath("$.data.datasetId").value(dataset.getId()))
                .andExpect(jsonPath("$.data.runId").value(run.getId()))
                .andExpect(jsonPath("$.data.promotedBy").value("manual-acceptance"))
                .andExpect(jsonPath("$.data.promotedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.createdAt").isNotEmpty());

        assertThat(datasets.findById(dataset.getId()).orElseThrow().getCurrentBaselineRunId())
                .isEqualTo(run.getId());
        assertThat(baselines.findByDatasetId(dataset.getId()))
                .singleElement()
                .satisfies(baseline -> {
                    assertThat(baseline.getId()).isNotNull();
                    assertThat(baseline.getPromotedAt()).isNotNull();
                    assertThat(baseline.getCreatedAt()).isNotNull();
                });

        mvc.perform(get("/api/evaluation/datasets/{id}/baseline", dataset.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.baselineId").isNumber())
                .andExpect(jsonPath("$.data.datasetId").value(dataset.getId()))
                .andExpect(jsonPath("$.data.datasetKey").value(dataset.getDatasetKey()))
                .andExpect(jsonPath("$.data.datasetVersion").value(1))
                .andExpect(jsonPath("$.data.runId").value(run.getId()))
                .andExpect(jsonPath("$.data.promotedBy").value("manual-acceptance"));
    }

    @Test
    void rejectsFailedRunWithBusinessErrorInsteadOfInternalServerError() throws Exception {
        EvaluationDataset dataset = dataset();
        EvaluationRun run = run(dataset, "FAILED", 1, 0, 0);

        mvc.perform(post("/api/evaluation/runs/{id}/promote-baseline", run.getId())
                        .contentType("application/json")
                        .content("{\"promotedBy\":\"manual-acceptance\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EVALUATION_BASELINE_PROMOTION_NOT_ALLOWED"));

        assertThat(datasets.findById(dataset.getId()).orElseThrow().getCurrentBaselineRunId()).isNull();
        assertThat(baselines.findByDatasetId(dataset.getId())).isEmpty();
    }

    private EvaluationDataset dataset() {
        LocalDateTime now = LocalDateTime.now();
        EvaluationDataset dataset = new EvaluationDataset();
        dataset.setDatasetKey("baseline-api-" + System.nanoTime());
        dataset.setName("Baseline API acceptance");
        dataset.setVersion(1);
        dataset.setStatus("ACTIVE");
        dataset.setDescription("endpoint integration test");
        dataset.setCreatedAt(now);
        dataset.setUpdatedAt(now);
        return datasets.save(dataset);
    }

    private EvaluationRun run(EvaluationDataset dataset, String status,
            int failedCount, int errorCount, int skippedCount) {
        LocalDateTime now = LocalDateTime.now();
        EvaluationRun run = new EvaluationRun();
        run.setDatasetId(dataset.getId());
        run.setDatasetVersion(dataset.getVersion());
        run.setProviderMode("mock");
        run.setPromptStubVersion("stage8-default");
        run.setStatus(status);
        run.setTotalCount(1);
        run.setPassedCount("PASSED".equals(status) ? 1 : 0);
        run.setFailedCount(failedCount);
        run.setErrorCount(errorCount);
        run.setSkippedCount(skippedCount);
        run.setRegressionStatus("NOT_COMPARED");
        run.setRegressionCount(0);
        run.setImprovedCount(0);
        run.setNewCaseCount(0);
        run.setMissingCaseCount(0);
        run.setConfigJson("{}");
        run.setSummaryJson("{}");
        run.setStartedAt(now);
        run.setFinishedAt(now);
        run.setCreatedAt(now);
        return runs.save(run);
    }
}
