package com.jclaw.agent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AgentConfigRepository extends JpaRepository<AgentConfig, String> {

    List<AgentConfig> findByTrustLevel(AgentTrustLevel trustLevel);
}
