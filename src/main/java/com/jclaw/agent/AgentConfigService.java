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

    @PreAuthorize("hasAnyAuthority('SCOPE_jclaw.user', 'SCOPE_jclaw.operator', 'SCOPE_jclaw.admin', 'SCOPE_jclaw.service')")
    public AgentConfig getAgentConfig(String agentId) {
        return agentConfigRepository.findById(agentId).orElse(null);
    }

    @PreAuthorize("hasAnyAuthority('SCOPE_jclaw.user', 'SCOPE_jclaw.operator', 'SCOPE_jclaw.admin', 'SCOPE_jclaw.service')")
    public AgentConfig getOrCreateDefault(String agentId) {
        return agentConfigRepository.findById(agentId)
                .orElseGet(() -> {
                    AgentConfig config = new AgentConfig(agentId, agentId);
                    return agentConfigRepository.save(config);
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
                "{\"agentId\":\"" + agentId + "\"}");
    }

    private String getCurrentPrincipal() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }

    private String serializeConfigDetails(String agentId, AgentConfig config) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "agentId", agentId,
                    "trustLevel", config.getTrustLevel().name(),
                    "model", Optional.ofNullable(config.getModel()).orElse("default")
            ));
        } catch (Exception e) {
            return "{\"agentId\":\"" + agentId + "\"}";
        }
    }
}
