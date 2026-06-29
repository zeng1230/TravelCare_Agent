package travelcare_agent.evaluation.repository.mybatis;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Repository;
import travelcare_agent.evaluation.entity.*;
import travelcare_agent.evaluation.repository.*;

import java.util.*;

public final class MyBatisEvaluationRepositories {
    private MyBatisEvaluationRepositories() {
    }

    @Repository
    public static class DatasetRepo implements EvaluationDatasetRepository {
        private final EvaluationMappers.Dataset m;

        public DatasetRepo(EvaluationMappers.Dataset m) {
            this.m = m;
        }

        public EvaluationDataset save(EvaluationDataset v) {
            if (v.getId() == null) m.insert(v);
            else m.updateById(v);
            return v;
        }

        public Optional<EvaluationDataset> findById(Long id) {
            return Optional.ofNullable(m.selectById(id));
        }

        public Optional<EvaluationDataset> findByKeyAndVersion(String k, int v) {
            return Optional.ofNullable(m.selectOne(new LambdaQueryWrapper<EvaluationDataset>().eq(EvaluationDataset::getDatasetKey, k).eq(EvaluationDataset::getVersion, v).last("limit 1")));
        }

        public int findMaxVersion(String k) {
            return m.selectList(new LambdaQueryWrapper<EvaluationDataset>().eq(EvaluationDataset::getDatasetKey, k).orderByDesc(EvaluationDataset::getVersion).last("limit 1")).stream().map(EvaluationDataset::getVersion).findFirst().orElse(0);
        }
    }

    @Repository
    public static class CaseRepo implements EvaluationCaseRepository {
        private final EvaluationMappers.Case m;

        public CaseRepo(EvaluationMappers.Case m) {
            this.m = m;
        }

        public EvaluationCase save(EvaluationCase v) {
            if (v.getId() == null) m.insert(v);
            else m.updateById(v);
            return v;
        }

        public Optional<EvaluationCase> findById(Long id) {
            return Optional.ofNullable(m.selectById(id));
        }

        public Optional<EvaluationCase> findByDatasetIdAndCaseKey(Long d, String k) {
            return Optional.ofNullable(m.selectOne(new LambdaQueryWrapper<EvaluationCase>().eq(EvaluationCase::getDatasetId, d).eq(EvaluationCase::getCaseKey, k).last("limit 1")));
        }

        public List<EvaluationCase> findByDatasetId(Long d) {
            return m.selectList(new LambdaQueryWrapper<EvaluationCase>().eq(EvaluationCase::getDatasetId, d).orderByAsc(EvaluationCase::getId));
        }

        public List<EvaluationCase> findEnabledCasesByDatasetId(Long d) {
            return m.selectList(new LambdaQueryWrapper<EvaluationCase>().eq(EvaluationCase::getDatasetId, d).eq(EvaluationCase::getEnabled, true).orderByAsc(EvaluationCase::getId));
        }

        public void deleteById(Long id) {
            m.deleteById(id);
        }
    }

    @Repository
    public static class RunRepo implements EvaluationRunRepository {
        private final EvaluationMappers.Run m;

        public RunRepo(EvaluationMappers.Run m) {
            this.m = m;
        }

        public EvaluationRun save(EvaluationRun v) {
            if (v.getId() == null) m.insert(v);
            else m.updateById(v);
            return v;
        }

        public Optional<EvaluationRun> findById(Long id) {
            return Optional.ofNullable(m.selectById(id));
        }
    }

    @Repository
    public static class ResultRepo implements EvaluationCaseResultRepository {
        private final EvaluationMappers.Result m;

        public ResultRepo(EvaluationMappers.Result m) {
            this.m = m;
        }

        public EvaluationCaseResult save(EvaluationCaseResult v) {
            if (v.getId() == null) m.insert(v);
            else m.updateById(v);
            return v;
        }

        public List<EvaluationCaseResult> findResultsByRunId(Long id) {
            return m.selectList(new LambdaQueryWrapper<EvaluationCaseResult>().eq(EvaluationCaseResult::getRunId, id).orderByAsc(EvaluationCaseResult::getId));
        }

        public long countByRunIdAndStatus(Long id, String s) {
            return m.selectCount(new LambdaQueryWrapper<EvaluationCaseResult>().eq(EvaluationCaseResult::getRunId, id).eq(EvaluationCaseResult::getStatus, s));
        }
    }

    @Repository
    public static class BaselineRepo implements EvaluationBaselineRepository {
        private final EvaluationMappers.Baseline m;

        public BaselineRepo(EvaluationMappers.Baseline m) {
            this.m = m;
        }

        public EvaluationBaseline save(EvaluationBaseline v) {
            if (v.getId() == null) m.insert(v);
            else m.updateById(v);
            return v;
        }

        public Optional<EvaluationBaseline> findCurrent(Long d, Long r) {
            return Optional.ofNullable(m.selectOne(new LambdaQueryWrapper<EvaluationBaseline>().eq(EvaluationBaseline::getDatasetId, d).eq(EvaluationBaseline::getRunId, r).orderByDesc(EvaluationBaseline::getPromotedAt).last("limit 1")));
        }

        public List<EvaluationBaseline> findByDatasetId(Long d) {
            return m.selectList(new LambdaQueryWrapper<EvaluationBaseline>().eq(EvaluationBaseline::getDatasetId, d).orderByAsc(EvaluationBaseline::getPromotedAt));
        }
    }
}
