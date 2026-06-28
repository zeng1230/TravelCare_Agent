package travelcare_agent.outbox.mybatis;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Repository;
import travelcare_agent.outbox.OutboxEvent;
import travelcare_agent.outbox.OutboxEventRepository;
import travelcare_agent.outbox.OutboxEventStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class MyBatisOutboxEventRepository implements OutboxEventRepository {
    private final MyBatisOutboxEventMapper mapper;

    public MyBatisOutboxEventRepository(MyBatisOutboxEventMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public OutboxEvent save(OutboxEvent event) {
        LocalDateTime now = LocalDateTime.now();
        if (event.getId() == null) {
            if (event.getCreatedAt() == null) event.setCreatedAt(now);
            if (event.getUpdatedAt() == null) event.setUpdatedAt(now);
            mapper.insert(event);
        } else {
            event.setUpdatedAt(now);
            mapper.updateById(event);
        }
        return event;
    }

    @Override
    public Optional<OutboxEvent> findById(Long id) {
        return Optional.ofNullable(mapper.selectById(id));
    }

    @Override
    public Optional<OutboxEvent> findByDedupeKey(String dedupeKey) {
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<OutboxEvent>()
                .eq(OutboxEvent::getDedupeKey, dedupeKey)
                .last("limit 1")));
    }

    @Override
    public List<OutboxEvent> findDueEvents(LocalDateTime now, int limit) {
        return mapper.selectList(new LambdaQueryWrapper<OutboxEvent>()
                .in(OutboxEvent::getStatus, OutboxEventStatus.NEW, OutboxEventStatus.RETRYING)
                .and(wrapper -> wrapper.isNull(OutboxEvent::getNextRetryAt)
                        .or().le(OutboxEvent::getNextRetryAt, now))
                .orderByAsc(OutboxEvent::getCreatedAt)
                .last("limit " + Math.max(1, limit)));
    }

    @Override
    public boolean claimForPublishing(Long id, LocalDateTime now) {
        return mapper.update(null, new LambdaUpdateWrapper<OutboxEvent>()
                .set(OutboxEvent::getStatus, OutboxEventStatus.PUBLISHING)
                .set(OutboxEvent::getUpdatedAt, now)
                .eq(OutboxEvent::getId, id)
                .in(OutboxEvent::getStatus, OutboxEventStatus.NEW, OutboxEventStatus.RETRYING)) == 1;
    }

    @Override
    public List<OutboxEvent> findStalePublishing(LocalDateTime before) {
        return mapper.selectList(new LambdaQueryWrapper<OutboxEvent>()
                .eq(OutboxEvent::getStatus, OutboxEventStatus.PUBLISHING)
                .lt(OutboxEvent::getUpdatedAt, before));
    }

    @Override
    public long countBacklog() {
        return mapper.selectCount(new LambdaQueryWrapper<OutboxEvent>()
                .in(OutboxEvent::getStatus, OutboxEventStatus.NEW,
                        OutboxEventStatus.RETRYING, OutboxEventStatus.PUBLISHING));
    }
}
