package travelcare_agent.common.lock;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class RedisLockService implements LockService {

    private final StringRedisTemplate redisTemplate;

    private static final String RELEASE_LOCK_LUA_SCRIPT = 
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
            
    private final DefaultRedisScript<Long> releaseScript;

    public RedisLockService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.releaseScript = new DefaultRedisScript<>(RELEASE_LOCK_LUA_SCRIPT, Long.class);
    }

    @Override
    public boolean acquire(String key, String token, long ttlMillis) {
        Boolean success = redisTemplate.opsForValue().setIfAbsent(key, token, Duration.ofMillis(ttlMillis));
        return Boolean.TRUE.equals(success);
    }

    @Override
    public boolean release(String key, String token) {
        Long result = redisTemplate.execute(releaseScript, Collections.singletonList(key), token);
        return result != null && result > 0L;
    }

    @Override
    public <T> T withLock(String key, long ttlMillis, Supplier<T> action) {
        String token = UUID.randomUUID().toString();
        if (!acquire(key, token, ttlMillis)) {
            throw new IllegalStateException("Failed to acquire lock for key: " + key);
        }
        try {
            return action.get();
        } finally {
            release(key, token);
        }
    }
}
