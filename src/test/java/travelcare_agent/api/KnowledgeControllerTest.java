package travelcare_agent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import travelcare_agent.common.exception.GlobalExceptionHandler;
import travelcare_agent.common.result.ResultCode;
import travelcare_agent.retrieval.entity.KnowledgeDocument;
import travelcare_agent.retrieval.service.KnowledgeIngestionService;
import travelcare_agent.retrieval.service.RetrievalSnippet;
import travelcare_agent.retrieval.service.RetrievalService;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KnowledgeControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private KnowledgeIngestionService ingestionService;
    private RetrievalService retrievalService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ingestionService = mock(KnowledgeIngestionService.class);
        retrievalService = mock(RetrievalService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new KnowledgeController(ingestionService, retrievalService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void testIngestDocumentSuccess() throws Exception {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(101L);
        doc.setTitle("SOP");
        doc.setStatus("ACTIVE");

        when(ingestionService.ingest(anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(doc);

        KnowledgeController.IngestDocumentRequest request = new KnowledgeController.IngestDocumentRequest(
                "SOP", "REFUND_SOP", "http://uri", "Content paragraph.", null, null
        );

        mockMvc.perform(post("/api/knowledge/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.id").value(101L))
                .andExpect(jsonPath("$.data.title").value("SOP"));
    }

    @Test
    void testSearchKnowledgeSuccess() throws Exception {
        RetrievalSnippet snippet = new RetrievalSnippet(101L, 201L, "SOP", "Content", "http://uri", 1.0);
        when(retrievalService.retrieve(any())).thenReturn(List.of(snippet));

        mockMvc.perform(get("/api/knowledge/search")
                        .param("query", "delayed flight"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data[0].documentId").value(101L))
                .andExpect(jsonPath("$.data[0].chunkId").value(201L))
                .andExpect(jsonPath("$.data[0].content").value("Content"));
    }
}
