package travelcare_agent.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import travelcare_agent.agent.AgentContext;
import travelcare_agent.agent.ContextAssembler;
import travelcare_agent.common.exception.GlobalExceptionHandler;
import travelcare_agent.common.result.ResultCode;
import travelcare_agent.conversation.service.SessionEventService;
import travelcare_agent.conversation.service.SessionService;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SessionControllerContextTest {

    private SessionService sessionService;
    private SessionEventService eventService;
    private ContextAssembler contextAssembler;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        sessionService = mock(SessionService.class);
        eventService = mock(SessionEventService.class);
        contextAssembler = mock(ContextAssembler.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new SessionController(sessionService, eventService, contextAssembler))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void testGetSessionContextSuccess() throws Exception {
        AgentContext context = new AgentContext(List.of(), null, null, List.of(), List.of());
        when(contextAssembler.assemble(eq(100L), anyString())).thenReturn(context);

        mockMvc.perform(get("/api/sessions/{sessionId}/context", 100L)
                        .param("query", "delayed flight"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data").exists())
                .andExpect(jsonPath("$.data.recentEvents").isArray());
    }
}
