package travelcare_agent.config;

import org.springframework.context.annotation.*;

import java.time.Clock;

@Configuration
public class ClockConfig {
    @Bean
    public Clock applicationClock() {
        return Clock.systemDefaultZone();
    }
}
