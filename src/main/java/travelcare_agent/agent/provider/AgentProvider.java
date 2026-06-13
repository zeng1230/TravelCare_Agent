package travelcare_agent.agent.provider;

public interface AgentProvider {

    String name();

    AgentProviderResponse invoke(AgentProviderRequest request);
}
