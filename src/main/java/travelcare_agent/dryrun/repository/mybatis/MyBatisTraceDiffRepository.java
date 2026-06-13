package travelcare_agent.dryrun.repository.mybatis;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Repository;
import travelcare_agent.dryrun.entity.TraceDiff;
import travelcare_agent.dryrun.repository.TraceDiffRepository;

import java.util.Optional;

@Repository
public class MyBatisTraceDiffRepository implements TraceDiffRepository {
    private final MyBatisTraceDiffMapper mapper;
    public MyBatisTraceDiffRepository(MyBatisTraceDiffMapper mapper){this.mapper=mapper;}
    public TraceDiff save(TraceDiff value){if(value.getId()==null)mapper.insert(value);else mapper.updateById(value);return value;}
    public Optional<TraceDiff> find(String original,String dryRun){return Optional.ofNullable(mapper.selectOne(
            new LambdaQueryWrapper<TraceDiff>().eq(TraceDiff::getOriginalTraceId,original)
                    .eq(TraceDiff::getDryRunTraceId,dryRun).last("limit 1")));}
}
