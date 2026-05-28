package travelcare_agent.human.repository.mybatis;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Repository;
import travelcare_agent.enums.HumanReviewCaseStatus;
import travelcare_agent.human.entity.HumanReviewCase;
import travelcare_agent.human.repository.HumanReviewCaseRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class MyBatisHumanReviewCaseRepository implements HumanReviewCaseRepository {

    private final MyBatisHumanReviewCaseMapper mapper;

    public MyBatisHumanReviewCaseRepository(MyBatisHumanReviewCaseMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public HumanReviewCase save(HumanReviewCase hrCase) {
        if (hrCase.getId() == null) {
            if (hrCase.getCreatedAt() == null) {
                hrCase.setCreatedAt(LocalDateTime.now());
            }
            if (hrCase.getUpdatedAt() == null) {
                hrCase.setUpdatedAt(LocalDateTime.now());
            }
            mapper.insert(hrCase);
        } else {
            hrCase.setUpdatedAt(LocalDateTime.now());
            mapper.updateById(hrCase);
        }
        return hrCase;
    }

    @Override
    public Optional<HumanReviewCase> findById(Long id) {
        return Optional.ofNullable(mapper.selectById(id));
    }

    @Override
    public List<HumanReviewCase> findByStatus(HumanReviewCaseStatus status) {
        return mapper.selectList(new LambdaQueryWrapper<HumanReviewCase>()
                .eq(HumanReviewCase::getStatus, status));
    }

    @Override
    public Optional<HumanReviewCase> findByWorkflowId(Long workflowId) {
        List<HumanReviewCase> list = mapper.selectList(new LambdaQueryWrapper<HumanReviewCase>()
                .eq(HumanReviewCase::getWorkflowId, workflowId));
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }
}
