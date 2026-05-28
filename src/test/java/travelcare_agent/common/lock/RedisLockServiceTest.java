package travelcare_agent.common.lock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RedisLockServiceTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private RedisLockService lockService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lockService = new RedisLockService(redisTemplate);
    }

    @Test
    void acquire_ShouldReturnTrueWhenLockObtained() {
        when(valueOperations.setIfAbsent(eq("test:lock"), eq("token123"), any(Duration.class)))
                .thenReturn(true);

        boolean acquired = lockService.acquire("test:lock", "token123", 1000);
        assertThat(acquired).isTrue();
    }

    @Test
    void acquire_ShouldReturnFalseWhenLockConflict() {
        when(valueOperations.setIfAbsent(eq("test:lock"), eq("token123"), any(Duration.class)))
                .thenReturn(false);

        boolean acquired = lockService.acquire("test:lock", "token123", 1000);
        assertThat(acquired).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void release_ShouldReturnTrueWhenTokenMatches() {
        when(redisTemplate.execute(any(RedisScript.class), eq(Collections.singletonList("test:lock")), eq("token123")))
                .thenReturn(1L);

        boolean released = lockService.release("test:lock", "token123");
        assertThat(released).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void release_ShouldReturnFalseWhenTokenMismatch() {
        when(redisTemplate.execute(any(RedisScript.class), eq(Collections.singletonList("test:lock")), eq("token123")))
                .thenReturn(0L);

        boolean released = lockService.release("test:lock", "token123");
        assertThat(released).isFalse();
    }

    @Test
    void withLock_ShouldExecuteActionWhenLockAcquired() {
        when(valueOperations.setIfAbsent(eq("test:lock"), anyString(), any(Duration.class)))
                .thenReturn(true);
        when(redisTemplate.execute(any(RedisScript.class), eq(Collections.singletonList("test:lock")), anyString()))
                .thenReturn(1L);

        String result = lockService.withLock("test:lock", 1000, () -> "success");
        assertThat(result).isEqualTo("success");
    }

    @Test
    void withLock_ShouldThrowExceptionWhenLockConflict() {
        when(valueOperations.setIfAbsent(eq("test:lock"), anyString(), any(Duration.class)))
                .thenReturn(false);

        assertThatThrownBy(() -> lockService.withLock("test:lock", 1000, () -> "success"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to acquire lock for key: test:lock");
    }
}
