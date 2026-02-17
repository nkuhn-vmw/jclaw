package com.jclaw.agent;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "agent_configs")
public class AgentConfig {

    @Id
    @Column(name = "agent_id", length = 64)
    private String agentId;

    @Column(name = "display_name", length = 256)
    private String displayName;

    @Column(length = 128)
    private String model;

    @Enumerated(EnumType.STRING)
    @Column(name = "trust_level", nullable = false, length = 16)
    private AgentTrustLevel trustLevel = AgentTrustLevel.STANDARD;

    @Column(name = "system_prompt", length = 100000)
    private String systemPrompt;

    @Column(name = "allowed_tools")
    private String[] allowedTools;

    @Column(name = "denied_tools")
    private String[] deniedTools;

    @Column(name = "egress_allowlist")
    private String[] egressAllowlist;

    @Column(name = "max_tokens_per_request")
    private int maxTokensPerRequest = 4096;

    @Column(name = "max_tool_calls")
    private int maxToolCalls = 10;

    @Column(name = "max_history_tokens")
    private int maxHistoryTokens = 128000;

    @Column(name = "config_json", length = 10000)
    private String configJson = "{}";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public AgentConfig() {}

    public AgentConfig(String agentId, String displayName) {
        this.agentId = agentId;
        this.displayName = displayName;
    }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public AgentTrustLevel getTrustLevel() { return trustLevel; }
    public void setTrustLevel(AgentTrustLevel trustLevel) { this.trustLevel = trustLevel; }

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    public String[] getAllowedTools() { return allowedTools; }
    public void setAllowedTools(String[] allowedTools) { this.allowedTools = allowedTools; }

    public String[] getDeniedTools() { return deniedTools; }
    public void setDeniedTools(String[] deniedTools) { this.deniedTools = deniedTools; }

    public String[] getEgressAllowlist() { return egressAllowlist; }
    public void setEgressAllowlist(String[] egressAllowlist) { this.egressAllowlist = egressAllowlist; }

    public int getMaxTokensPerRequest() { return maxTokensPerRequest; }
    public void setMaxTokensPerRequest(int maxTokensPerRequest) { this.maxTokensPerRequest = maxTokensPerRequest; }

    public int getMaxToolCalls() { return maxToolCalls; }
    public void setMaxToolCalls(int maxToolCalls) { this.maxToolCalls = maxToolCalls; }

    public int getMaxHistoryTokens() { return maxHistoryTokens; }
    public void setMaxHistoryTokens(int maxHistoryTokens) { this.maxHistoryTokens = maxHistoryTokens; }

    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public boolean isToolAllowed(String toolName) {
        if (deniedTools != null) {
            for (String denied : deniedTools) {
                if (denied.equals(toolName)) return false;
            }
        }
        if (allowedTools == null || allowedTools.length == 0) return true;
        for (String allowed : allowedTools) {
            if (allowed.equals(toolName)) return true;
        }
        return false;
    }
}
