package travelcare_agent.dryrun.repository.mybatis;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import travelcare_agent.dryrun.entity.TraceDiff;

@Mapper
public interface MyBatisTraceDiffMapper extends BaseMapper<TraceDiff> {
}
