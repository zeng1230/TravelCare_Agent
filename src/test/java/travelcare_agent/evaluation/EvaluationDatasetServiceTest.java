package travelcare_agent.evaluation;

import org.junit.jupiter.api.Test;
import travelcare_agent.evaluation.entity.EvaluationCase;
import travelcare_agent.evaluation.entity.EvaluationDataset;
import travelcare_agent.evaluation.repository.EvaluationCaseRepository;
import travelcare_agent.evaluation.repository.EvaluationDatasetRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationDatasetServiceTest {
    @Test
    void draftCanMutateCasesAndCloneCopiesCases() {
        DatasetRepo datasets = new DatasetRepo();
        CaseRepo cases = new CaseRepo();
        EvaluationDatasetService service = new EvaluationDatasetService(datasets, cases,
                Clock.fixed(Instant.parse("2026-06-15T00:00:00Z"), ZoneOffset.UTC));
        EvaluationDataset dataset = service.create("refund", "Refund", "desc");
        EvaluationCase created = service.createCase(dataset.getId(), "eligible", "Eligible", 11L,
                "{\"expectedPolicyDecision\":\"ELIGIBLE\"}", "[]", true);

        service.updateCase(dataset.getId(), created.getId(), "eligible", "Updated", 11L,
                "{\"expectedPolicyDecision\":\"ELIGIBLE\"}", "[]", true);
        EvaluationDataset clone = service.cloneVersion(dataset.getId());

        assertThat(clone.getVersion()).isEqualTo(2);
        assertThat(clone.getStatus()).isEqualTo("DRAFT");
        assertThat(cases.findByDatasetId(clone.getId())).extracting(EvaluationCase::getCaseKey)
                .containsExactly("eligible");
    }

    @Test
    void activeDatasetRejectsCaseMutation() {
        DatasetRepo datasets = new DatasetRepo();
        CaseRepo cases = new CaseRepo();
        EvaluationDatasetService service = new EvaluationDatasetService(datasets, cases, Clock.systemUTC());
        EvaluationDataset dataset = service.create("refund", "Refund", null);
        service.activate(dataset.getId());
        assertThatThrownBy(() -> service.createCase(dataset.getId(), "x", "X", 1L, "{}", null, true))
                .isInstanceOf(travelcare_agent.common.exception.BusinessException.class)
                .hasMessageContaining("not draft");
    }

    private static final class DatasetRepo implements EvaluationDatasetRepository {
        private final Map<Long, EvaluationDataset> values = new LinkedHashMap<>(); private long id;
        public EvaluationDataset save(EvaluationDataset v){if(v.getId()==null)v.setId(++id);values.put(v.getId(),v);return v;}
        public Optional<EvaluationDataset> findById(Long id){return Optional.ofNullable(values.get(id));}
        public Optional<EvaluationDataset> findByKeyAndVersion(String key,int version){return values.values().stream().filter(v->key.equals(v.getDatasetKey())&&version==v.getVersion()).findFirst();}
        public int findMaxVersion(String key){return values.values().stream().filter(v->key.equals(v.getDatasetKey())).mapToInt(EvaluationDataset::getVersion).max().orElse(0);}
    }
    private static final class CaseRepo implements EvaluationCaseRepository {
        private final Map<Long,EvaluationCase> values=new LinkedHashMap<>();private long id;
        public EvaluationCase save(EvaluationCase v){if(v.getId()==null)v.setId(++id);values.put(v.getId(),v);return v;}
        public Optional<EvaluationCase> findById(Long id){return Optional.ofNullable(values.get(id));}
        public Optional<EvaluationCase> findByDatasetIdAndCaseKey(Long d,String k){return values.values().stream().filter(v->d.equals(v.getDatasetId())&&k.equals(v.getCaseKey())).findFirst();}
        public List<EvaluationCase> findByDatasetId(Long d){return values.values().stream().filter(v->d.equals(v.getDatasetId())).toList();}
        public List<EvaluationCase> findEnabledCasesByDatasetId(Long d){return findByDatasetId(d).stream().filter(v->Boolean.TRUE.equals(v.getEnabled())).toList();}
        public void deleteById(Long id){values.remove(id);}
    }
}
