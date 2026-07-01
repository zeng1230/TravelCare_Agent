package travelcare_supplier.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SupplierOrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void successScenarioReturnsOrder() throws Exception {
        mockMvc.perform(get("/supplier/orders/ORD-1001")
                        .queryParam("userId", "1001")
                        .queryParam("scenario", "success")
                        .header("X-Trace-Id", "trace-success"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", "trace-success"))
                .andExpect(jsonPath("$.orderNo").value("ORD-1001"))
                .andExpect(jsonPath("$.refundable").value(true));
    }

    @Test
    void notFoundScenarioReturnsStandard404() throws Exception {
        mockMvc.perform(get("/supplier/orders/ORD-1001")
                        .queryParam("userId", "1001")
                        .queryParam("scenario", "not_found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void timeoutScenarioDelaysThenReturnsTimeoutError() throws Exception {
        mockMvc.perform(get("/supplier/orders/ORD-1001")
                        .queryParam("userId", "1001")
                        .queryParam("scenario", "timeout"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("SUPPLIER_TIMEOUT"));
    }

    @Test
    void serverErrorScenarioReturnsStandard500() throws Exception {
        mockMvc.perform(get("/supplier/orders/ORD-1001")
                        .queryParam("userId", "1001")
                        .queryParam("scenario", "server_error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("SUPPLIER_INTERNAL_ERROR"));
    }

    @Test
    void malformedScenarioReturnsInvalidJsonBody() throws Exception {
        mockMvc.perform(get("/supplier/orders/ORD-1001")
                        .queryParam("userId", "1001")
                        .queryParam("scenario", "malformed"))
                .andExpect(status().isOk())
                .andExpect(content().string("{"));
    }

    @Test
    void missingFieldScenarioOmitsRequiredStatusField() throws Exception {
        mockMvc.perform(get("/supplier/orders/ORD-1001")
                        .queryParam("userId", "1001")
                        .queryParam("scenario", "missing_field"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNo").value("ORD-1001"))
                .andExpect(jsonPath("$.status").doesNotExist());
    }

    @Test
    void missingTraceIdGeneratesResponseHeader() throws Exception {
        mockMvc.perform(get("/supplier/orders/ORD-1001")
                        .queryParam("userId", "1001"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", not(blankOrNullString())));
    }

    @Test
    void actuatorHealthIsExposed() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
