package travelcare_agent.observability;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import travelcare_agent.trace.RedactionBoundary;

public class RedactedMessageConverter extends MessageConverter {
    private static final int DEFAULT_LIMIT = 2048;

    @Override
    public String convert(ILoggingEvent event) {
        return RedactionBoundary.sanitizeLogField(event == null ? null : event.getFormattedMessage(), DEFAULT_LIMIT);
    }
}
