package travelcare_agent.evaluation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import travelcare_agent.evaluation.entity.*;
import travelcare_agent.evaluation.repository.*;
import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.common.result.ResultCode;

import java.time.*;
import java.util.*;

@Service
public class EvaluationDatasetService {
    private final EvaluationDatasetRepository datasets;
    private final EvaluationCaseRepository cases;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public EvaluationDatasetService(EvaluationDatasetRepository d, EvaluationCaseRepository c) {
        this(d, c, Clock.systemDefaultZone());
    }

    public EvaluationDatasetService(EvaluationDatasetRepository d, EvaluationCaseRepository c, Clock clock) {
        datasets = d;
        cases = c;
        this.clock = clock;
    }

    @Transactional
    public EvaluationDataset create(String key, String name, String description) {
        if (key == null || key.isBlank() || name == null || name.isBlank())
            throw new IllegalArgumentException("datasetKey and name are required");
        EvaluationDataset v = new EvaluationDataset();
        v.setDatasetKey(key);
        v.setName(name);
        v.setDescription(description);
        v.setVersion(datasets.findMaxVersion(key) + 1);
        v.setStatus("DRAFT");
        v.setCreatedAt(now());
        v.setUpdatedAt(now());
        return datasets.save(v);
    }

    public EvaluationDataset get(Long id) {
        return datasets.findById(id).orElseThrow(() -> new BusinessException(ResultCode.EVALUATION_DATASET_NOT_FOUND));
    }

    @Transactional
    public EvaluationDataset activate(Long id) {
        EvaluationDataset v = get(id);
        v.setStatus("ACTIVE");
        v.setUpdatedAt(now());
        return datasets.save(v);
    }

    @Transactional
    public EvaluationDataset cloneVersion(Long id) {
        EvaluationDataset src = get(id);
        EvaluationDataset dst = create(src.getDatasetKey(), src.getName(), src.getDescription());
        dst.setClonedFromDatasetId(src.getId());
        datasets.save(dst);
        for (EvaluationCase c : cases.findByDatasetId(id))
            createCase(dst.getId(), c.getCaseKey(), c.getName(), c.getSourceTraceId(), c.getExpectationJson(), c.getTagsJson(), c.getEnabled());
        return dst;
    }

    @Transactional
    public EvaluationCase createCase(Long datasetId, String key, String name, Long source, String expectation, String tags, boolean enabled) {
        requireDraft(datasetId);
        if (key == null || key.isBlank() || name == null || name.isBlank() || source == null || expectation == null || expectation.isBlank())
            throw new BusinessException(ResultCode.EVALUATION_INVALID_EXPECTATION);
        if (cases.findByDatasetIdAndCaseKey(datasetId, key).isPresent())
            throw new BusinessException(ResultCode.EVALUATION_CASE_KEY_DUPLICATED);
        EvaluationCase v = new EvaluationCase();
        v.setDatasetId(datasetId);
        v.setCaseKey(key);
        v.setName(name);
        v.setSourceTraceId(source);
        v.setExpectationJson(expectation);
        v.setTagsJson(tags);
        v.setEnabled(enabled);
        v.setCreatedAt(now());
        v.setUpdatedAt(now());
        return cases.save(v);
    }

    @Transactional
    public EvaluationCase updateCase(Long datasetId, Long id, String key, String name, Long source, String expectation, String tags, boolean enabled) {
        requireDraft(datasetId);
        EvaluationCase v = caseInDataset(datasetId, id);
        cases.findByDatasetIdAndCaseKey(datasetId, key).filter(x -> !x.getId().equals(id)).ifPresent(x -> {
            throw new BusinessException(ResultCode.EVALUATION_CASE_KEY_DUPLICATED);
        });
        v.setCaseKey(key);
        v.setName(name);
        v.setSourceTraceId(source);
        v.setExpectationJson(expectation);
        v.setTagsJson(tags);
        v.setEnabled(enabled);
        v.setUpdatedAt(now());
        return cases.save(v);
    }

    @Transactional
    public void deleteCase(Long datasetId, Long id) {
        requireDraft(datasetId);
        caseInDataset(datasetId, id);
        cases.deleteById(id);
    }

    public List<EvaluationCase> cases(Long datasetId) {
        get(datasetId);
        return cases.findByDatasetId(datasetId);
    }

    private EvaluationDataset requireDraft(Long id) {
        EvaluationDataset v = get(id);
        if (!"DRAFT".equals(v.getStatus())) throw new BusinessException(ResultCode.EVALUATION_DATASET_NOT_DRAFT);
        return v;
    }

    private EvaluationCase caseInDataset(Long d, Long id) {
        EvaluationCase v = cases.findById(id).orElseThrow(() -> new BusinessException(ResultCode.EVALUATION_CASE_NOT_FOUND));
        if (!d.equals(v.getDatasetId())) throw new BusinessException(ResultCode.EVALUATION_CASE_NOT_FOUND);
        return v;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
