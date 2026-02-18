package com.jclaw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "jclaw")
public class JclawProperties {

    private SessionProperties session = new SessionProperties();
    private SecurityProperties security = new SecurityProperties();
    private GenAiProperties genai = new GenAiProperties();
    private List<AgentProperties> agents = new ArrayList<>();

    public SessionProperties getSession() { return session; }
    public void setSession(SessionProperties session) { this.session = session; }

    public SecurityProperties getSecurity() { return security; }
    public void setSecurity(SecurityProperties security) { this.security = security; }

    public GenAiProperties getGenai() { return genai; }
    public void setGenai(GenAiProperties genai) { this.genai = genai; }

    public List<AgentProperties> getAgents() { return agents; }
    public void setAgents(List<AgentProperties> agents) { this.agents = agents; }

    public static class SessionProperties {
        private String defaultScope = "MAIN";
        private String groupScope = "GROUP";
        private int maxHistoryTokens = 128000;
        private int compactionThresholdTokens = 96000;
        private int idleTimeoutMinutes = 1440;

        public String getDefaultScope() { return defaultScope; }
        public void setDefaultScope(String defaultScope) { this.defaultScope = defaultScope; }
        public String getGroupScope() { return groupScope; }
        public void setGroupScope(String groupScope) { this.groupScope = groupScope; }
        public int getMaxHistoryTokens() { return maxHistoryTokens; }
        public void setMaxHistoryTokens(int maxHistoryTokens) { this.maxHistoryTokens = maxHistoryTokens; }
        public int getCompactionThresholdTokens() { return compactionThresholdTokens; }
        public void setCompactionThresholdTokens(int t) { this.compactionThresholdTokens = t; }
        public int getIdleTimeoutMinutes() { return idleTimeoutMinutes; }
        public void setIdleTimeoutMinutes(int idleTimeoutMinutes) { this.idleTimeoutMinutes = idleTimeoutMinutes; }
    }

    public static class SecurityProperties {
        private DataRetentionProperties dataRetention = new DataRetentionProperties();
        private PiiProperties pii = new PiiProperties();
        private DataResidencyProperties dataResidency = new DataResidencyProperties();
        private RateLimitProperties rateLimit = new RateLimitProperties();

        public DataRetentionProperties getDataRetention() { return dataRetention; }
        public void setDataRetention(DataRetentionProperties dataRetention) { this.dataRetention = dataRetention; }
        public PiiProperties getPii() { return pii; }
        public void setPii(PiiProperties pii) { this.pii = pii; }
        public DataResidencyProperties getDataResidency() { return dataResidency; }
        public void setDataResidency(DataResidencyProperties dataResidency) { this.dataResidency = dataResidency; }
        public RateLimitProperties getRateLimit() { return rateLimit; }
        public void setRateLimit(RateLimitProperties rateLimit) { this.rateLimit = rateLimit; }
    }

    public static class RateLimitProperties {
        private int userPerMinute = 20;
        private int userPerHour = 200;
        private int servicePerMinute = 60;
        private int servicePerHour = 1000;

        public int getUserPerMinute() { return userPerMinute; }
        public void setUserPerMinute(int v) { this.userPerMinute = v; }
        public int getUserPerHour() { return userPerHour; }
        public void setUserPerHour(int v) { this.userPerHour = v; }
        public int getServicePerMinute() { return servicePerMinute; }
        public void setServicePerMinute(int v) { this.servicePerMinute = v; }
        public int getServicePerHour() { return servicePerHour; }
        public void setServicePerHour(int v) { this.servicePerHour = v; }
    }

    public static class DataResidencyProperties {
        private String region = "us";

        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }
    }

    public static class DataRetentionProperties {
        private int sessionTranscriptsDays = 90;
        private int auditLogDays = 365;
        private int contentFilterEventsDays = 30;

        public int getSessionTranscriptsDays() { return sessionTranscriptsDays; }
        public void setSessionTranscriptsDays(int d) { this.sessionTranscriptsDays = d; }
        public int getAuditLogDays() { return auditLogDays; }
        public void setAuditLogDays(int d) { this.auditLogDays = d; }
        public int getContentFilterEventsDays() { return contentFilterEventsDays; }
        public void setContentFilterEventsDays(int d) { this.contentFilterEventsDays = d; }
    }

    public static class PiiProperties {
        private boolean redactInLogs = true;
        private List<String> redactPatterns = new ArrayList<>();

        public boolean isRedactInLogs() { return redactInLogs; }
        public void setRedactInLogs(boolean redactInLogs) { this.redactInLogs = redactInLogs; }
        public List<String> getRedactPatterns() { return redactPatterns; }
        public void setRedactPatterns(List<String> redactPatterns) { this.redactPatterns = redactPatterns; }
    }

    public static class GenAiProperties {
        private String apiBase;
        private String apiKey;
        private String model = "claude-sonnet-4-20250514";

        public String getApiBase() { return apiBase; }
        public void setApiBase(String apiBase) { this.apiBase = apiBase; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
    }

    public static class AgentProperties {
        private String id;
        private String displayName;
        private String model;
        private String trustLevel = "STANDARD";
        private String systemPromptRef;
        private List<String> allowedTools = new ArrayList<>();
        private List<String> egressAllowlist = new ArrayList<>();
        private List<ChannelBinding> channels = new ArrayList<>();

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getTrustLevel() { return trustLevel; }
        public void setTrustLevel(String trustLevel) { this.trustLevel = trustLevel; }
        public String getSystemPromptRef() { return systemPromptRef; }
        public void setSystemPromptRef(String systemPromptRef) { this.systemPromptRef = systemPromptRef; }
        public List<String> getAllowedTools() { return allowedTools; }
        public void setAllowedTools(List<String> allowedTools) { this.allowedTools = allowedTools; }
        public List<String> getEgressAllowlist() { return egressAllowlist; }
        public void setEgressAllowlist(List<String> egressAllowlist) { this.egressAllowlist = egressAllowlist; }
        public List<ChannelBinding> getChannels() { return channels; }
        public void setChannels(List<ChannelBinding> channels) { this.channels = channels; }
    }

    public static class ChannelBinding {
        private String type;
        private String workspace;
        private List<String> channels = new ArrayList<>();
        private String activation = "MENTION";

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getWorkspace() { return workspace; }
        public void setWorkspace(String workspace) { this.workspace = workspace; }
        public List<String> getChannels() { return channels; }
        public void setChannels(List<String> channels) { this.channels = channels; }
        public String getActivation() { return activation; }
        public void setActivation(String activation) { this.activation = activation; }
    }
}
