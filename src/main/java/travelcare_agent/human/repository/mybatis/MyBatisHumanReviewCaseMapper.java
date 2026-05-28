package travelcare_agent.human.repository.mybatis;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import travelcare_agent.human.entity.HumanReviewCase;

@Mapper
public interface MyBatisHumanReviewCaseMapper extends BaseMapper<HumanReviewCase> {
}
