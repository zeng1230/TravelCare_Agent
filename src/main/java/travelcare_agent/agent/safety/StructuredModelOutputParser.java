package travelcare_agent.agent.safety;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class StructuredModelOutputParser {
    static final int MAX_TEXT_LENGTH = 4096;
    static final int MAX_COLLECTION_SIZE = 20;
    static final int MAX_JSON_LENGTH = 65_536;

    private final ObjectReader reader;
    private final ObjectMapper strictMapper;

    public StructuredModelOutputParser(ObjectMapper objectMapper) {
        this.strictMapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        strictMapper.coercionConfigFor(LogicalType.Float)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail);
        strictMapper.coercionConfigFor(LogicalType.Integer)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail);
        strictMapper.coercionConfigFor(LogicalType.Boolean)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail);
        this.reader = strictMapper.readerFor(StructuredModelOutput.class);
    }

    public StructuredModelOutput parse(String content) {
        if (content == null || content.isBlank()) {
            throw new ModelOutputParseException("EMPTY_OUTPUT", "Model output is empty");
        }
        if (content.length() > MAX_JSON_LENGTH) {
            throw new ModelOutputParseException("OUTPUT_TOO_LARGE", "Model output exceeds the size limit");
        }
        try {
            JsonNode root = strictMapper.readTree(content);
            if (root == null || !root.isObject()) throw invalidType();
            for (String required : List.of("intent", "confidence", "slots", "citations", "riskFlags")) {
                if (!root.has(required)) {
                    throw new ModelOutputParseException(
                            "MISSING_REQUIRED_FIELD", "Model output is missing a required contract field");
                }
            }
        } catch (ModelOutputParseException ex) {
            throw ex;
        } catch (JsonProcessingException ex) {
            throw new ModelOutputParseException("INVALID_JSON", "Model output is not valid JSON", ex);
        }
        final StructuredModelOutput output;
        try {
            output = reader.readValue(content);
        } catch (JsonMappingException ex) {
            String reason = ex.getMessage() != null && ex.getMessage().contains("Unrecognized field")
                    ? "UNKNOWN_FIELD" : "INVALID_TYPE";
            throw new ModelOutputParseException(reason, "Model output does not match the structured contract", ex);
        } catch (JsonProcessingException ex) {
            throw new ModelOutputParseException("INVALID_JSON", "Model output is not valid JSON", ex);
        }
        validate(output);
        return output;
    }

    private static void validate(StructuredModelOutput output) {
        if (output == null) {
            throw new ModelOutputParseException("INVALID_TYPE", "Model output must be an object");
        }
        if (output.confidence() == null || !Double.isFinite(output.confidence())
                || output.confidence() < 0.0 || output.confidence() > 1.0) {
            throw new ModelOutputParseException("INVALID_CONFIDENCE", "Confidence must be between zero and one");
        }
        checkLength(output.intent(), 64);
        checkLength(output.answerDraft(), MAX_TEXT_LENGTH);
        checkLength(output.refusalReason(), 512);
        checkLength(output.handoffReason(), 512);
        checkLength(output.slots().orderNo(), 128);
        checkCollection(output.citations());
        checkCollection(output.riskFlags());
        for (CitationRef citation : output.citations()) {
            if (citation == null) throw invalidType();
            checkLength(citation.retrievalRunId(), 128);
        }
        for (RiskFlag risk : output.riskFlags()) {
            if (risk == null || risk.severity() == null) throw invalidType();
            checkLength(risk.code(), 64);
            checkLength(risk.reason(), 512);
        }
        ToolProposal proposal = output.toolProposal();
        if (proposal != null) {
            checkLength(proposal.toolName(), 64);
            checkLength(proposal.actionType(), 64);
            checkLength(proposal.idempotencyScope(), 128);
            if (proposal.arguments() != null) checkLength(proposal.arguments().orderNo(), 128);
        }
    }

    private static void checkCollection(List<?> values) {
        if (values.size() > MAX_COLLECTION_SIZE) {
            throw new ModelOutputParseException("COLLECTION_TOO_LARGE", "Model output collection exceeds the limit");
        }
    }

    private static void checkLength(String value, int maxLength) {
        if (value != null && value.length() > maxLength) {
            throw new ModelOutputParseException("FIELD_TOO_LONG", "Model output field exceeds the length limit");
        }
    }

    private static ModelOutputParseException invalidType() {
        return new ModelOutputParseException("INVALID_TYPE", "Model output contains an invalid value");
    }
}
