package travelcare_agent.agent.prompt;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Service
public class PromptTemplateService {

    public static final String INTENT_CLASSIFIER_V1 = "intent-classifier-v1";
    public static final String RESPONSE_GENERATOR_V1 = "response-generator-v1";

    public String render(String promptVersion, String inputJson) {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/" + promptVersion + ".txt");
            String template = resource.getContentAsString(StandardCharsets.UTF_8);
            return template.replace("{{input_json}}", inputJson == null ? "{}" : inputJson);
        } catch (IOException ex) {
            throw new IllegalStateException("PROMPT_TEMPLATE_NOT_FOUND: " + promptVersion);
        }
    }
}
