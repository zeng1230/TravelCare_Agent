package travelcare_agent.adapter.order;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

class SupplierOrderAdapterConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AdapterTestConfiguration.class);

    @Test
    void httpModeCreatesHttpSupplierOrderAdapter() {
        contextRunner
                .withPropertyValues(
                        "travelcare.supplier.mode=http",
                        "travelcare.supplier.gateway.base-url=http://localhost:8081"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OrderAdapter.class);
                    assertThat(context).hasSingleBean(HttpSupplierOrderAdapter.class);
                    assertThat(context).hasSingleBean(SupplierGatewayHealthIndicator.class);
                    assertThat(context).doesNotHaveBean(MockOrderAdapter.class);
                    assertThat(context.getBean(OrderAdapter.class)).isInstanceOf(HttpSupplierOrderAdapter.class);
                });
    }

    @Test
    void defaultModeCreatesMockOrderAdapter() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OrderAdapter.class);
            assertThat(context).hasSingleBean(MockOrderAdapter.class);
            assertThat(context).doesNotHaveBean(HttpSupplierOrderAdapter.class);
            assertThat(context).doesNotHaveBean(SupplierGatewayHealthIndicator.class);
            assertThat(context.getBean(OrderAdapter.class)).isInstanceOf(MockOrderAdapter.class);
        });
    }

    @Configuration
    @Import({HttpSupplierOrderAdapter.class, MockOrderAdapter.class, SupplierGatewayProperties.class,
            SupplierGatewayHealthIndicator.class})
    static class AdapterTestConfiguration {
        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }
    }
}
