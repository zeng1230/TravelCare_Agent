package travelcare_agent.agent.provider;

public interface ChatModelProvider {

    ModelResponse call(ModelRequest request);

    String providerName();
}
