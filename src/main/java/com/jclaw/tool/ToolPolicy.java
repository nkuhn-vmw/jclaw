package com.jclaw.tool;

import com.jclaw.agent.AgentConfig;
import com.jclaw.agent.AgentTrustLevel;
import org.springframework.stereotype.Component;

@Component
public class ToolPolicy {

    public boolean isToolAllowed(String toolName, RiskLevel riskLevel,
                                  boolean requiresApproval, AgentConfig agentConfig) {
        if (agentConfig == null) return riskLevel == RiskLevel.LOW;

        // Deny list takes precedence
        if (agentConfig.getDeniedTools() != null && agentConfig.getDeniedTools().contains(toolName)) {
            return false;
        }

        // Trust level restrictions — enforced as a hard ceiling before allow-list
        AgentTrustLevel trustLevel = agentConfig.getTrustLevel();

        switch (trustLevel) {
            case RESTRICTED:
                // RESTRICTED agents can only use LOW-risk tools — hard ceiling, allow-list cannot override
                if (riskLevel != RiskLevel.LOW) return false;
                break;
            case STANDARD:
                // STANDARD agents can use LOW and MEDIUM tools, not HIGH
                if (riskLevel == RiskLevel.HIGH) return false;
                break;
            case ELEVATED:
                // ELEVATED agents can use all tool risk levels
                break;
        }

        // Tools that require human approval are blocked unless trust level is ELEVATED
        if (requiresApproval && trustLevel != AgentTrustLevel.ELEVATED) {
            return false;
        }

        // Explicit allow list overrides default tool routing (within trust-level ceiling above)
        boolean explicitlyAllowed = agentConfig.getAllowedTools() != null
                && !agentConfig.getAllowedTools().isEmpty()
                && agentConfig.getAllowedTools().contains(toolName);
        if (explicitlyAllowed) {
            return true;
        }

        return true;
    }
}
