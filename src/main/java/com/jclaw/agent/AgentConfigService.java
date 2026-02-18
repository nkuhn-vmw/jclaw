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

    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_jclaw.admin')")
    public AgentConfig updateAgentConfig(String agentId, AgentConfig config) {
        String principal = getCurrentPrincipal();
        config.setAgentId(agentId);
        config.setUpdatedAt(Instant.now());
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
