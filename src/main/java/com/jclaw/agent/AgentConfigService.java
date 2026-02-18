package com.jclaw.agent;

import com.jclaw.audit.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class AgentConfigService {

    private static final Logger log = LoggerFactory.getLogger(AgentConfigService.class);

    private final AgentConfigRepository agentConfigRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public AgentConfigService(AgentConfigRepository agentConfigRepository,
                             AuditService auditService,
                             ObjectMapper objectMapper) {
        this.agentConfigRepository = agentConfigRepository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * Internal read method — no @PreAuthorize because this is called from agent runtime
     * threads (boundedElastic) where Spring Security context is unavailable.
     * HTTP-layer security is enforced at the controller level.
     */
    public AgentConfig getAgentConfig(String agentId) {
        return agentConfigRepository.findById(agentId).orElse(null);
    }

    /**
     * Internal read/create method — no @PreAuthorize for the same reason as getAgentConfig.
     */
    @Transactional
    public AgentConfig getOrCreateDefault(String agentId) {
        return agentConfigRepository.findById(agentId)
                .orElseGet(() -> {
                    try {
                        AgentConfig config = new AgentConfig(agentId, agentId);
                        return agentConfigRepository.save(config);
                    } catch (org.springframework.dao.DataIntegrityViolationException e) {
                        // Concurrent creation race — re-fetch the row created by the other transaction
                        return agentConfigRepository.findById(agentId)
                                .orElseThrow(() -> new IllegalStateException(
                                        "Agent config disappeared after concurrent create for: " + agentId));
                    }
                });
    }

    @PreAuthorize("hasAnyAuthority('SCOPE_jclaw.operator', 'SCOPE_jclaw.admin')")
    public List<AgentConfig> getAllConfigs() {
        return agentConfigRepository.findAll();
    }

    private static final int MIN_TOOL_CALLS = 1;
    private static final int MAX_TOOL_CALLS = 100;
    private static final int MIN_TOKENS = 256;
    private static final int MAX_TOKENS = 200_000;
    private static final int MAX_HISTORY_TOKENS = 1_000_000;
    private static final int MAX_SYSTEM_PROMPT_LENGTH = 50_000;

    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_jclaw.admin')")
    public AgentConfig updateAgentConfig(String agentId, AgentConfig config) {
        String principal = getCurrentPrincipal();
        config.setAgentId(agentId);
        config.setUpdatedAt(Instant.now());

        // Clamp safety-critical numeric bounds
        if (config.getMaxToolCallsPerRequest() < MIN_TOOL_CALLS) config.setMaxToolCallsPerRequest(MIN_TOOL_CALLS);
        if (config.getMaxToolCallsPerRequest() > MAX_TOOL_CALLS) config.setMaxToolCallsPerRequest(MAX_TOOL_CALLS);
        if (config.getMaxTokensPerRequest() < MIN_TOKENS) config.setMaxTokensPerRequest(MIN_TOKENS);
        if (config.getMaxTokensPerRequest() > MAX_TOKENS) config.setMaxTokensPerRequest(MAX_TOKENS);
        if (config.getMaxHistoryTokens() < MIN_TOKENS) config.setMaxHistoryTokens(MIN_TOKENS);
        if (config.getMaxHistoryTokens() > MAX_HISTORY_TOKENS) config.setMaxHistoryTokens(MAX_HISTORY_TOKENS);
        if (config.getSystemPrompt() != null && config.getSystemPrompt().length() > MAX_SYSTEM_PROMPT_LENGTH) {
            config.setSystemPrompt(config.getSystemPrompt().substring(0, MAX_SYSTEM_PROMPT_LENGTH));
        }

        AgentConfig saved = agentConfigRepository.save(config);
        auditService.logConfigChange(principal, agentId, "AGENT_CONFIG_UPDATE",
                serializeConfigDetails(agentId, config));
        return saved;
    }

    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_jclaw.admin')")
    public void deleteAgentConfig(String agentId) {
        String principal = getCurrentPrincipal();
        agentConfigRepository.deleteById(agentId);
        auditService.logConfigChange(principal, agentId, "AGENT_CONFIG_DELETE",
                serializeSafe(java.util.Map.of("agentId", agentId)));
    }

    private String getCurrentPrincipal() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }

    private String serializeSafe(java.util.Map<String, String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String serializeConfigDetails(String agentId, AgentConfig config) {
        return serializeSafe(java.util.Map.of(
                "agentId", agentId,
                "trustLevel", config.getTrustLevel().name(),
                "model", Optional.ofNullable(config.getModel()).orElse("default")
        ));
    }
}
