package travelcare_agent.refund.repository.mybatis;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import travelcare_agent.refund.entity.RefundCase;

@Mapper
public interface MyBatisRefundCaseMapper extends BaseMapper<RefundCase> {
}
