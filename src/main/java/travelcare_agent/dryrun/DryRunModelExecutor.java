package travelcare_agent.dryrun;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import travelcare_agent.agent.provider.AgentProviderRequest;
import travelcare_agent.agent.provider.MockAgentProvider;

import java.util.Map;

@Component
public class DryRunModelExecutor {
    private final MockAgentProvider provider; private final ObjectMapper objectMapper;
    public DryRunModelExecutor(MockAgentProvider provider,ObjectMapper objectMapper){this.provider=provider;this.objectMapper=objectMapper;}
    public ModelResult generate(String deterministicAnswer){
        return generate(deterministicAnswer,"stage7b-dry-run");
    }
    public ModelResult generate(String deterministicAnswer,String promptVersion){
        try{var response=provider.invoke(new AgentProviderRequest("RESPONSE_GENERATION",promptVersion,"dry-run",Map.of("deterministicAnswer",deterministicAnswer)));
            JsonNode output=objectMapper.readTree(response.rawText());return new ModelResult(provider.name(),response.model(),output.path("answer").asText(),output);}
        catch(Exception ex){throw new IllegalStateException("DRY_RUN_MODEL_FAILED",ex);}
    }
    public record ModelResult(String provider,String model,String answer,JsonNode output){}
}
