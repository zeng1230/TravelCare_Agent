package travelcare_agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.common.exception.GlobalExceptionHandler;
import travelcare_agent.common.result.Result;
import travelcare_agent.common.result.ResultCode;
import travelcare_agent.common.trace.TraceIdFilter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest
class TravelCareAgentApplicationTests {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new InfraDemoController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilter(new TraceIdFilter())
                .build();
    }

    @Test
    void contextLoads() {
    }

    @Test
    void resultIncludesRequestTraceIdAndWritesItToLogs(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/infra-demo/success")
                        .header(TraceIdFilter.TRACE_ID_HEADER, "trace-demo-001")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string(TraceIdFilter.TRACE_ID_HEADER, "trace-demo-001"))
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.code()))
                .andExpect(jsonPath("$.message").value(ResultCode.SUCCESS.message()))
                .andExpect(jsonPath("$.data").value("ok"))
                .andExpect(jsonPath("$.traceId").value("trace-demo-001"));

        assertThat(output).contains("trace-demo-001");
    }

    @Test
    void businessExceptionIsCapturedAsUnifiedResult() throws Exception {
        mockMvc.perform(get("/infra-demo/business-error")
                        .header(TraceIdFilter.TRACE_ID_HEADER, "trace-business-001")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(header().string(TraceIdFilter.TRACE_ID_HEADER, "trace-business-001"))
                .andExpect(jsonPath("$.code").value(ResultCode.ORDER_NOT_FOUND.code()))
                .andExpect(jsonPath("$.message").value("order missing"))
                .andExpect(jsonPath("$.traceId").value("trace-business-001"));
    }

    @Test
    void unknownExceptionIsCapturedAsUnifiedResultAndTraceIdIsGenerated() throws Exception {
        mockMvc.perform(get("/infra-demo/system-error")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(TraceIdFilter.TRACE_ID_HEADER, not(blankOrNullString())))
                .andExpect(jsonPath("$.code").value(ResultCode.INTERNAL_ERROR.code()))
                .andExpect(jsonPath("$.message").value(ResultCode.INTERNAL_ERROR.message()))
                .andExpect(jsonPath("$.traceId").value(not(blankOrNullString())));
    }

    @RestController
    static class InfraDemoController {

        private static final Logger log = LoggerFactory.getLogger(InfraDemoController.class);

        @GetMapping("/infra-demo/success")
        Result<String> success() {
            log.info("infra demo success");
            return Result.success("ok");
        }

        @GetMapping("/infra-demo/business-error")
        Result<Void> businessError() {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND, "order missing");
        }

        @GetMapping("/infra-demo/system-error")
        Result<Void> systemError() {
            throw new IllegalStateException("boom");
        }
    }
}
