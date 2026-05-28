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
    public RefundCase save(RefundCase refundCase) {
        if (refundCase.getId() == null) {
            mapper.insert(refundCase);
        } else {
            mapper.updateById(refundCase);
        }
        return refundCase;
    }

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

