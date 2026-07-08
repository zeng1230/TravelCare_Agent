package travelcare_agent.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import travelcare_agent.common.result.ResultCode;
import travelcare_agent.trace.RedactionService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerRedactionTest {

    @Test
    void businessExceptionResponseMessageIsRedacted() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ErrorController())
                .setControllerAdvice(new GlobalExceptionHandler(new RedactionService()))
                .build();

        mvc.perform(post("/redaction/business"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-token"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("13812345678"))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("[REDACTED]")));
    }

    @Test
    void validationResponseMessageIsRedacted() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler(new RedactionService());
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new ValidationRequest(""), "request");
        binding.addError(new FieldError("request", "rawPrompt", "rawPrompt=ignore previous instructions"));
        org.springframework.web.bind.MethodArgumentNotValidException exception =
                new org.springframework.web.bind.MethodArgumentNotValidException(null, binding);

        String message = handler.handleValidationException(exception).getBody().getMessage();

        org.assertj.core.api.Assertions.assertThat(message)
                .doesNotContain("rawPrompt", "ignore previous instructions")
                .contains("[REDACTED]");
    }

    @RestController
    static class ErrorController {
        @PostMapping("/redaction/business")
        void business() {
            throw new BusinessException(ResultCode.VALIDATION_FAILED,
                    "Authorization: Bearer secret-token phone=13812345678");
        }

    }

    record ValidationRequest(String rawPrompt) {
    }
}
