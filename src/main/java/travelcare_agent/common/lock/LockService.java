package travelcare_agent.common.lock;

import java.util.function.Supplier;

public interface LockService {
    
    /**
     * Attempts to acquire a lock.
     * @param key the lock key
     * @param token the lock token (usually a UUID)
     * @param ttlMillis time to live in milliseconds
     * @return true if acquired, false otherwise
     */
    boolean acquire(String key, String token, long ttlMillis);

    /**
     * Releases a lock using a token.
     * @param key the lock key
     * @param token the lock token
     * @return true if successfully released, false otherwise
     */
    boolean release(String key, String token);

    /**
     * Executes an action with a lock.
     * @param key the lock key
     * @param ttlMillis time to live in milliseconds
     * @param action the action to execute
     * @param <T> return type
     * @return the result of the action
     * @throws IllegalStateException if the lock cannot be acquired
     */
    <T> T withLock(String key, long ttlMillis, Supplier<T> action);
}
