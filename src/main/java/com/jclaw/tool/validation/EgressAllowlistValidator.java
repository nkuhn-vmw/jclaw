package com.jclaw.tool.validation;

import com.jclaw.agent.AgentConfig;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.regex.Pattern;

@Component
public class EgressAllowlistValidator {

    public boolean isAllowed(String url, AgentConfig agentConfig) {
        if (agentConfig == null) {
            return false; // deny if no agent config at all
        }
        if (agentConfig.getEgressAllowlist() == null || agentConfig.getEgressAllowlist().isEmpty()) {
            return false; // deny by default — explicit allowlist required per spec §5.3
        }

        try {
            URI uri = URI.create(url);
            // Enforce HTTPS scheme only (per spec §5.3)
            if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
            // Block URLs with userinfo component (potential host confusion attack)
            if (uri.getUserInfo() != null) return false;
            String host = uri.getHost();
            if (host == null) return false;

            for (String pattern : agentConfig.getEgressAllowlist()) {
                if (matchesPattern(host, pattern)) return true;
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    private boolean matchesPattern(String host, String pattern) {
        if (pattern.startsWith("*.")) {
            String suffix = pattern.substring(1); // ".example.com"
            return host.endsWith(suffix) || host.equals(pattern.substring(2));
        }
        return host.equals(pattern);
    }
}
