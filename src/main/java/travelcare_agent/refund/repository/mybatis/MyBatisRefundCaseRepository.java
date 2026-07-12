package travelcare_agent.refund.repository.mybatis;

import org.springframework.stereotype.Repository;
import travelcare_agent.refund.entity.RefundCase;
import travelcare_agent.refund.repository.RefundCaseRepository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.List;
import java.util.Optional;

@Repository
public class MyBatisRefundCaseRepository implements RefundCaseRepository {

    private final MyBatisRefundCaseMapper mapper;

    public MyBatisRefundCaseRepository(MyBatisRefundCaseMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public RefundCase insert(RefundCase refundCase) {
        if (refundCase.getVersion() == null) refundCase.setVersion(0L);
        mapper.insert(refundCase);
        return refundCase;
    }

    @Override public int decideIfNeedHuman(RefundCase c, long v) { return mapper.decideIfNeedHuman(c, v); }

    @Override
    public Optional<RefundCase> findById(Long id) {
        return Optional.ofNullable(mapper.selectById(id));
    }

    @Override
    public Optional<RefundCase> findByWorkflowId(Long workflowId) {
        List<RefundCase> list = mapper.selectList(new LambdaQueryWrapper<RefundCase>()
                .eq(RefundCase::getWorkflowId, workflowId));
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }
}
