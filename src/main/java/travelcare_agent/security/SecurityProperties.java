package travelcare_agent.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "travelcare.security")
public class SecurityProperties {
    private boolean devAuthEnabled;

    public boolean isDevAuthEnabled() {
        return devAuthEnabled;
    }

    public void setDevAuthEnabled(boolean devAuthEnabled) {
        this.devAuthEnabled = devAuthEnabled;
    }
}
