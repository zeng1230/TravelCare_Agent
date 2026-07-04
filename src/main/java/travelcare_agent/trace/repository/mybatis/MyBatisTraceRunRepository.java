package travelcare_agent.trace.repository.mybatis;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Repository;
import travelcare_agent.trace.entity.TraceRun;
import travelcare_agent.trace.repository.TraceRunRepository;
import java.util.List;
import java.util.Optional;
@Repository public class MyBatisTraceRunRepository implements TraceRunRepository {
    private final MyBatisTraceRunMapper mapper;
    public MyBatisTraceRunRepository(MyBatisTraceRunMapper mapper){
        this.mapper=mapper;
    }

    public TraceRun save(TraceRun v){
        if(v.getId()==null)
            mapper.insert(v);
        else
            mapper.updateById(v);return v;
    }

    public Optional<TraceRun> findById(Long id){
        return Optional.ofNullable(mapper.selectById(id));
    }

    public Optional<TraceRun> findByTraceId(String id){
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<TraceRun>().eq(TraceRun::getTraceId,id).last("limit 1")));
    }

    public Optional<TraceRun> findLatestBySessionIdAndWorkflowId(Long sid,Long wid){
        LambdaQueryWrapper<TraceRun> wrapper=new LambdaQueryWrapper<TraceRun>()
                .eq(TraceRun::getSessionId,sid);
        if(wid!=null)wrapper.eq(TraceRun::getWorkflowId,wid);
        wrapper.orderByDesc(TraceRun::getStartedAt).orderByDesc(TraceRun::getId).last("limit 1");
        return Optional.ofNullable(mapper.selectOne(wrapper));
    }

    public List<TraceRun> findBySessionId(Long sid,long pn,long ps){
        return mapper.selectPage(Page.of(pn,ps,false),new LambdaQueryWrapper<TraceRun>().eq(TraceRun::getSessionId,sid).orderByDesc(TraceRun::getStartedAt).orderByDesc(TraceRun::getId)).getRecords();
    }

    public long countBySessionId(Long sid){
        return mapper.selectCount(new LambdaQueryWrapper<TraceRun>().eq(TraceRun::getSessionId,sid));
    }
}
