package travelcare_agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TravelCareAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(TravelCareAgentApplication.class, args);
    }

}

