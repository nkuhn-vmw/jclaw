package com.jclaw.agent;

import com.jclaw.audit.AuditService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class AgentConfigService {

    private final AgentConfigRepository agentConfigRepository;
    private final AuditService auditService;

    public AgentConfigService(AgentConfigRepository agentConfigRepository,
                             AuditService auditService) {
        this.agentConfigRepository = agentConfigRepository;
        this.auditService = auditService;
    }

    public AgentConfig getAgentConfig(String agentId) {
        return agentConfigRepository.findById(agentId).orElse(null);
    }

    public AgentConfig getOrCreateDefault(String agentId) {
        return agentConfigRepository.findById(agentId)
                .orElseGet(() -> {
                    AgentConfig config = new AgentConfig(agentId, agentId);
                    return agentConfigRepository.save(config);
                });
    }

    public List<AgentConfig> getAllConfigs() {
        return agentConfigRepository.findAll();
    }

    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_jclaw.admin')")
    public AgentConfig updateAgentConfig(String agentId, AgentConfig config) {
        config.setAgentId(agentId);
        config.setUpdatedAt(Instant.now());
        AgentConfig saved = agentConfigRepository.save(config);
        auditService.logConfigChange(null, agentId, "AGENT_CONFIG_UPDATE",
                "{\"agentId\":\"" + agentId + "\"}");
        return saved;
    }

    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_jclaw.admin')")
    public void deleteAgentConfig(String agentId) {
        agentConfigRepository.deleteById(agentId);
        auditService.logConfigChange(null, agentId, "AGENT_CONFIG_DELETE",
                "{\"agentId\":\"" + agentId + "\"}");
    }
}
