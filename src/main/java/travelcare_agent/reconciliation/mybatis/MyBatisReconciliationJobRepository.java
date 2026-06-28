package travelcare_agent.reconciliation.mybatis;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Repository;
import travelcare_agent.reconciliation.ReconciliationJob;
import travelcare_agent.reconciliation.ReconciliationJobStatus;
import travelcare_agent.reconciliation.ReconciliationJobRepository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public class MyBatisReconciliationJobRepository implements ReconciliationJobRepository {
    private final MyBatisReconciliationJobMapper mapper;

    public MyBatisReconciliationJobRepository(MyBatisReconciliationJobMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ReconciliationJob save(ReconciliationJob job) {
        LocalDateTime now = LocalDateTime.now();
        if (job.getId() == null) {
            job.setCreatedAt(now);
            job.setUpdatedAt(now);
            mapper.insert(job);
        } else {
            job.setUpdatedAt(now);
            mapper.updateById(job);
        }
        return job;
    }

    @Override
    public Optional<ReconciliationJob> findById(Long id) {
        return Optional.ofNullable(mapper.selectById(id));
    }

    @Override
    public Optional<ReconciliationJob> findBySource(String sourceType, Long sourceId) {
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<ReconciliationJob>()
                .eq(ReconciliationJob::getSourceType, sourceType)
                .eq(ReconciliationJob::getSourceId, sourceId)
                .last("limit 1")));
    }

    @Override
    public long countPending() {
        return mapper.selectCount(new LambdaQueryWrapper<ReconciliationJob>()
                .eq(ReconciliationJob::getStatus, ReconciliationJobStatus.PENDING));
    }
}
