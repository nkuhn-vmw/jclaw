package com.jclaw.agent;

import com.jclaw.config.JclawProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;

/**
 * Seeds agent configurations from YAML properties (jclaw.agents[]) into the database
 * at startup. Only creates records that don't already exist (won't overwrite DB edits).
 */
@Component
public class AgentConfigSeeder {

    private static final Logger log = LoggerFactory.getLogger(AgentConfigSeeder.class);

    private final JclawProperties properties;
    private final AgentConfigRepository agentConfigRepository;

    public AgentConfigSeeder(JclawProperties properties,
                            AgentConfigRepository agentConfigRepository) {
        this.properties = properties;
        this.agentConfigRepository = agentConfigRepository;
    }

    @PostConstruct
    @Transactional
    public void seedFromYaml() {
        if (properties.getAgents() == null || properties.getAgents().isEmpty()) {
            log.debug("No YAML agent configs to seed");
            return;
        }

        int seeded = 0;
        for (JclawProperties.AgentProperties agentProps : properties.getAgents()) {
            if (agentProps.getId() == null || agentProps.getId().isBlank()) {
                log.warn("Skipping YAML agent config with missing id");
                continue;
            }

            if (agentConfigRepository.existsById(agentProps.getId())) {
                log.debug("Agent config already exists in DB, skipping seed: {}", agentProps.getId());
                continue;
            }

            AgentConfig config = new AgentConfig(agentProps.getId(),
                    agentProps.getDisplayName() != null ? agentProps.getDisplayName() : agentProps.getId());

            if (agentProps.getModel() != null) {
                config.setModel(agentProps.getModel());
            }
            if (agentProps.getTrustLevel() != null) {
                config.setTrustLevel(AgentTrustLevel.valueOf(agentProps.getTrustLevel()));
            }
            if (agentProps.getAllowedTools() != null && !agentProps.getAllowedTools().isEmpty()) {
                config.setAllowedTools(new HashSet<>(agentProps.getAllowedTools()));
            }
            if (agentProps.getDeniedTools() != null && !agentProps.getDeniedTools().isEmpty()) {
                config.setDeniedTools(new HashSet<>(agentProps.getDeniedTools()));
            }
            if (agentProps.getEgressAllowlist() != null && !agentProps.getEgressAllowlist().isEmpty()) {
                config.setEgressAllowlist(new HashSet<>(agentProps.getEgressAllowlist()));
            }

            agentConfigRepository.save(config);
            seeded++;
            log.info("Seeded agent config from YAML: id={} displayName={} model={}",
                    agentProps.getId(), config.getDisplayName(), config.getModel());
        }

        if (seeded > 0) {
            log.info("Seeded {} agent configs from YAML properties", seeded);
        }
    }
}
