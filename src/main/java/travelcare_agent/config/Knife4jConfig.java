package travelcare_agent.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Knife4jConfig {

    @Bean
    public OpenAPI travelCareOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("TravelCare Agent API")
                        .description("TravelCare Agent monolith API documentation")
                        .version("0.0.1"));
    }
}
