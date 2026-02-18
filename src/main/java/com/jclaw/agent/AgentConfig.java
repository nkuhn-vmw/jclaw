package com.jclaw.agent;

import com.jclaw.content.ContentFilterPolicy;
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

    @Column(name = "system_prompt", columnDefinition = "text")
    private String systemPrompt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_allowed_tools",
            joinColumns = @JoinColumn(name = "agent_id"))
    @Column(name = "tool_name")
    private Set<String> allowedTools = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_denied_tools",
            joinColumns = @JoinColumn(name = "agent_id"))
    @Column(name = "tool_name")
    private Set<String> deniedTools = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_egress_allowlist",
            joinColumns = @JoinColumn(name = "agent_id"))
    @Column(name = "pattern")
    private Set<String> egressAllowlist = new HashSet<>();

    @Column(name = "max_tokens_per_request")
    private int maxTokensPerRequest = 4096;

    @Column(name = "max_tool_calls")
    private int maxToolCallsPerRequest = 10;

    @Column(name = "max_history_tokens")
    private int maxHistoryTokens = 128000;

    @Embedded
    private ContentFilterPolicy contentFilterPolicy = new ContentFilterPolicy();

    @Column(name = "config_json", columnDefinition = "jsonb")
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

    public Set<String> getAllowedTools() { return allowedTools; }
    public void setAllowedTools(Set<String> allowedTools) { this.allowedTools = allowedTools; }

    public Set<String> getDeniedTools() { return deniedTools; }
    public void setDeniedTools(Set<String> deniedTools) { this.deniedTools = deniedTools; }

    public Set<String> getEgressAllowlist() { return egressAllowlist; }
    public void setEgressAllowlist(Set<String> egressAllowlist) { this.egressAllowlist = egressAllowlist; }

    public int getMaxTokensPerRequest() { return maxTokensPerRequest; }
    public void setMaxTokensPerRequest(int maxTokensPerRequest) { this.maxTokensPerRequest = maxTokensPerRequest; }

    public int getMaxToolCallsPerRequest() { return maxToolCallsPerRequest; }
    public void setMaxToolCallsPerRequest(int maxToolCallsPerRequest) { this.maxToolCallsPerRequest = maxToolCallsPerRequest; }

    public int getMaxHistoryTokens() { return maxHistoryTokens; }
    public void setMaxHistoryTokens(int maxHistoryTokens) { this.maxHistoryTokens = maxHistoryTokens; }

    public ContentFilterPolicy getContentFilterPolicy() { return contentFilterPolicy; }
    public void setContentFilterPolicy(ContentFilterPolicy contentFilterPolicy) { this.contentFilterPolicy = contentFilterPolicy; }

    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public boolean isToolAllowed(String toolName) {
        if (deniedTools != null && deniedTools.contains(toolName)) return false;
        if (allowedTools == null || allowedTools.isEmpty()) return true;
        return allowedTools.contains(toolName);
    }
}
