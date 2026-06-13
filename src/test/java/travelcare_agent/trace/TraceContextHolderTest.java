package travelcare_agent.trace;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.assertThat;

class TraceContextHolderTest {
    @Test
    void threadLocalContextIsNotUsedForAsyncPropagation() {
        try (TraceContextHolder.Scope ignored=TraceContextHolder.attach("trace-1","span-1")) {
            assertThat(TraceContextHolder.current()).isNotNull();
            assertThat(CompletableFuture.supplyAsync(TraceContextHolder::current).join()).isNull();
        }
        assertThat(TraceContextHolder.current()).isNull();
    }
}
