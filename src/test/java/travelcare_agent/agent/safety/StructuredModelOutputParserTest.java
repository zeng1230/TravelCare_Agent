package travelcare_agent.agent.safety;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructuredModelOutputParserTest {

    private final StructuredModelOutputParser parser =
            new StructuredModelOutputParser(new ObjectMapper().findAndRegisterModules());

    @Test
    void parsesControlledStructuredOutput() {
        StructuredModelOutput output = parser.parse("""
                {
                  "intent":"REFUND_INQUIRY",
                  "confidence":0.91,
                  "slots":{"orderNo":"ORD-10","orderId":10},
                  "answerDraft":"Please let me check the refund rules.",
                  "citations":[{"retrievalRunId":"run-1","documentId":21,"chunkId":31}],
                  "riskFlags":[],
                  "toolProposal":{
                    "toolName":"GetOrderTool",
                    "actionType":"READ_ORDER",
                    "arguments":{"orderNo":"ORD-10","orderId":10},
                    "requiresConfirmation":false,
                    "idempotencyScope":"ORDER_LOOKUP"
                  },
                  "refusalReason":null,
                  "handoffReason":null
                }
                """);

        assertThat(output.intent()).isEqualTo("REFUND_INQUIRY");
        assertThat(output.confidence()).isEqualTo(0.91);
        assertThat(output.slots().orderNo()).isEqualTo("ORD-10");
        assertThat(output.citations()).singleElement().extracting(CitationRef::chunkId).isEqualTo(31L);
    }

    @Test
    void rejectsUnknownFields() {
        assertRejected("""
                {"intent":"ORDER_QUERY","confidence":0.9,"slots":{},"answerDraft":"ok",
                 "citations":[],"riskFlags":[],"unknown":"must fail"}
                """, "UNKNOWN_FIELD");
    }

    @Test
    void rejectsWrongTypes() {
        assertRejected("""
                {"intent":"ORDER_QUERY","confidence":"high","slots":{},"answerDraft":"ok",
                 "citations":[],"riskFlags":[]}
                """, "INVALID_TYPE");
    }

    @Test
    void rejectsIllegalConfidence() {
        assertRejected("""
                {"intent":"ORDER_QUERY","confidence":1.01,"slots":{},"answerDraft":"ok",
                 "citations":[],"riskFlags":[]}
                """, "INVALID_CONFIDENCE");
    }

    @Test
    void rejectsMissingRequiredContractFields() {
        assertRejected("""
                {"intent":"ORDER_QUERY","confidence":0.9,"answerDraft":"ok"}
                """, "MISSING_REQUIRED_FIELD");
    }

    @Test
    void rejectsOversizedText() {
        String json = """
                {"intent":"ORDER_QUERY","confidence":0.9,"slots":{},"answerDraft":"%s",
                 "citations":[],"riskFlags":[]}
                """.formatted("x".repeat(4097));

        assertRejected(json, "FIELD_TOO_LONG");
    }

    @Test
    void rejectsOversizedCollections() {
        String citations = java.util.stream.LongStream.rangeClosed(1, 21)
                .mapToObj(id -> "{\"retrievalRunId\":\"run-1\",\"documentId\":1,\"chunkId\":" + id + "}")
                .collect(java.util.stream.Collectors.joining(","));
        String json = """
                {"intent":"ORDER_QUERY","confidence":0.9,"slots":{},"answerDraft":"ok",
                 "citations":[%s],"riskFlags":[]}
                """.formatted(citations);

        assertRejected(json, "COLLECTION_TOO_LARGE");
    }

    private void assertRejected(String json, String reasonCode) {
        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(ModelOutputParseException.class)
                .extracting(error -> ((ModelOutputParseException) error).reasonCode())
                .isEqualTo(reasonCode);
    }
}
