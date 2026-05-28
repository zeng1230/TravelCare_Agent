package travelcare_agent.tool.repository.mybatis;

import org.springframework.stereotype.Repository;
import travelcare_agent.tool.entity.IdempotencyKey;
import travelcare_agent.tool.repository.IdempotencyKeyRepository;

import java.util.Optional;

@Repository
public class MyBatisIdempotencyKeyRepository implements IdempotencyKeyRepository {

    private final MyBatisIdempotencyKeyMapper mapper;

    public MyBatisIdempotencyKeyRepository(MyBatisIdempotencyKeyMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public IdempotencyKey save(IdempotencyKey idempotencyKey) {
        if (mapper.selectById(idempotencyKey.getIdempotencyKey()) == null) {
            mapper.insert(idempotencyKey);
        } else {
            mapper.updateById(idempotencyKey);
        }
        return idempotencyKey;
    }

    @Override
    public Optional<IdempotencyKey> findByKey(String idempotencyKey) {
        return Optional.ofNullable(mapper.selectById(idempotencyKey));
    }
}
