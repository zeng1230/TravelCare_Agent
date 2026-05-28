package travelcare_agent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import travelcare_agent.common.exception.GlobalExceptionHandler;
import travelcare_agent.common.result.ResultCode;
import travelcare_agent.enums.MemoryType;
import travelcare_agent.memory.entity.AgentMemory;
import travelcare_agent.memory.service.MemoryService;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MemoryControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MemoryService memoryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        memoryService = mock(MemoryService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new MemoryController(memoryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void testGetActiveMemoriesSuccess() throws Exception {
        AgentMemory memory = new AgentMemory();
        memory.setId(101L);
        memory.setUserId(1001L);
        memory.setMemoryKey("key");
        memory.setMemoryValue("val");

        when(memoryService.getActiveMemories(eq(1001L), any(), anyInt()))
                .thenReturn(List.of(memory));

        mockMvc.perform(get("/api/memories/users/{userId}", 1001L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data[0].id").value(101L))
                .andExpect(jsonPath("$.data[0].userId").value(1001L))
                .andExpect(jsonPath("$.data[0].memoryKey").value("key"));
    }

    @Test
    void testCreateManualMemorySuccess() throws Exception {
        AgentMemory memory = new AgentMemory();
        memory.setId(101L);
        memory.setUserId(1001L);
        memory.setMemoryKey("key");
        memory.setMemoryValue("val");

        when(memoryService.createMemory(eq(1001L), any(), any(), any(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(memory);

        MemoryController.CreateManualMemoryRequest request = new MemoryController.CreateManualMemoryRequest(
                MemoryType.USER_PREFERENCE, "key", "val", BigDecimal.ONE, null
        );

        mockMvc.perform(post("/api/memories/users/{userId}", 1001L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.id").value(101L))
                .andExpect(jsonPath("$.data.memoryKey").value("key"));
    }
}
