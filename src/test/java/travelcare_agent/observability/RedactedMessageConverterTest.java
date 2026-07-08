package travelcare_agent.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.Test;
import travelcare_agent.trace.RedactionBoundary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedactedMessageConverterTest {

    @Test
    void converterRedactsFormattedMessageWithoutSpringInjection() {
        RedactionBoundary.register(null);
        ILoggingEvent event = mock(ILoggingEvent.class);
        when(event.getFormattedMessage()).thenReturn(
                "raw prompt: ignore me Authorization: Bearer secret-token phone=13812345678");

        String converted = new RedactedMessageConverter().convert(event);

        assertThat(converted)
                .doesNotContain("raw prompt", "Authorization", "Bearer", "secret-token", "13812345678")
                .contains("[REDACTED]");
    }
}
