package travelcare_agent.agent.provider;

import org.junit.jupiter.api.Test;
import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.common.result.ResultCode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeepSeekAgentProviderTest {

    @Test
    void rejectsMissingApiKeyWithExplicitSafeError() {
        DeepSeekAgentProvider provider = new DeepSeekAgentProvider(
                new AgentProviderProperties.DeepSeek("https://api.deepseek.com", "", "deepseek-chat", 3000)
        );

        assertThatThrownBy(() -> provider.invoke(new AgentProviderRequest(
                "INTENT_CLASSIFICATION",
                "intent-classifier-v1",
                "prompt",
                Map.of("message", "refund ORD-10")
        )))
                .isInstanceOf(BusinessException.class)
                .extracting("resultCode")
                .isEqualTo(ResultCode.DEEPSEEK_API_KEY_MISSING);
    }
}
