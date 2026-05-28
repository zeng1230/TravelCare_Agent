package travelcare_agent.tool.repository.mybatis;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import travelcare_agent.tool.entity.IdempotencyKey;

@Mapper
public interface MyBatisIdempotencyKeyMapper extends BaseMapper<IdempotencyKey> {
}
