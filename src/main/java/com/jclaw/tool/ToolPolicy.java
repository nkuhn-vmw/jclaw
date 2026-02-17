package com.jclaw.tool;

import com.jclaw.agent.AgentConfig;
import com.jclaw.agent.AgentTrustLevel;
import org.springframework.stereotype.Component;

@Component
public class ToolPolicy {

    public boolean isToolAllowed(String toolName, RiskLevel riskLevel, AgentConfig agentConfig) {
        if (agentConfig == null) return riskLevel == RiskLevel.LOW;

        // Deny list takes precedence
        if (!agentConfig.isToolAllowed(toolName)) return false;

        // Trust level restrictions
        if (agentConfig.getTrustLevel() == AgentTrustLevel.RESTRICTED
                && riskLevel != RiskLevel.LOW) {
            return false;
        }

        return true;
    }
}
