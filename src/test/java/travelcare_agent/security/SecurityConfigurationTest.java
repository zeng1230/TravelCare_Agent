package travelcare_agent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;
import travelcare_agent.api.DevAuthController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityConfigurationTest {

    @Test
    void productionRejectsMissingDefaultOrShortJwtSecret() {
        JwtProperties properties = new JwtProperties();
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");

        assertThatThrownBy(() -> properties.validate(prod))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Production JWT secret");

        properties.setSecret("short-secret");
        assertThatThrownBy(() -> properties.validate(prod))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Production JWT secret");
    }

    @Test
    void devTokenEndpointRequiresLocalLikeProfileAndExplicitEnableFlag() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(DevAuthController.class, DevAuthTestConfig.class)
                .withPropertyValues(
                        "travelcare.jwt.secret=" + SecurityTestTokenFactory.SECRET,
                        "travelcare.security.dev-auth-enabled=true"
                );

        runner.withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context).doesNotHaveBean(DevAuthController.class));

        runner.withPropertyValues("spring.profiles.active=test", "travelcare.security.dev-auth-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(DevAuthController.class));
    }

    @Configuration
    static class DevAuthTestConfig {
        @Bean
        JwtProperties jwtProperties() {
            JwtProperties properties = new JwtProperties();
            properties.setSecret(SecurityTestTokenFactory.SECRET);
            return properties;
        }

        @Bean
        JwtTokenService jwtTokenService(JwtProperties properties, ObjectMapper objectMapper) {
            MockEnvironment test = new MockEnvironment();
            test.setActiveProfiles("test");
            return new JwtTokenService(properties, objectMapper, test);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
