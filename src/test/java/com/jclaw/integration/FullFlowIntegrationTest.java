package com.jclaw.integration;

import com.jclaw.agent.AgentConfig;
import com.jclaw.agent.AgentConfigRepository;
import com.jclaw.audit.AuditRepository;
import com.jclaw.security.IdentityMapping;
import com.jclaw.security.IdentityMappingRepository;
import com.jclaw.session.Session;
import com.jclaw.session.SessionRepository;
import com.jclaw.session.SessionScope;
import com.jclaw.session.SessionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
@org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase(replace = org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE)
class FullFlowIntegrationTest {

    @Autowired private SessionRepository sessionRepository;
    @Autowired private AgentConfigRepository agentConfigRepository;
    @Autowired private IdentityMappingRepository identityMappingRepository;
    @Autowired private AuditRepository auditRepository;

    @Test
    void sessionLifecycle() {
        Session session = new Session("agent1", "slack", "user@test.com", SessionScope.MAIN);
        session.setChannelConversationId("C123");

        Session saved = sessionRepository.save(session);
        assertNotNull(saved.getId());
        assertEquals(SessionStatus.ACTIVE, saved.getStatus());
        assertEquals(0, saved.getMessageCount());

        // Update
        saved.incrementMessageCount();
        saved.addTokens(150);
        saved.touch();
        sessionRepository.save(saved);

        Session found = sessionRepository.findById(saved.getId()).orElseThrow();
        assertEquals(1, found.getMessageCount());
        assertEquals(150, found.getTotalTokens());
    }

    @Test
    void agentConfigCrud() {
        AgentConfig config = new AgentConfig("ops-agent", "Ops Assistant");
        config.setModel("claude-sonnet-4-20250514");
        config.setAllowedTools(java.util.Set.of("web_search", "http_fetch"));

        agentConfigRepository.save(config);

        AgentConfig found = agentConfigRepository.findById("ops-agent").orElseThrow();
        assertEquals("Ops Assistant", found.getDisplayName());
        assertTrue(found.isToolAllowed("web_search"));
        assertFalse(found.isToolAllowed("data_query"));
    }

    @Test
    void identityMappingWorkflow() {
        IdentityMapping mapping = new IdentityMapping("slack", "U123", "user@corp.com");
        mapping.setDisplayName("Test User");

        identityMappingRepository.save(mapping);

        // Initially not approved
        var found = identityMappingRepository
                .findByChannelTypeAndChannelUserId("slack", "U123")
                .orElseThrow();
        assertFalse(found.isApproved());

        // Approve
        found.setApproved(true);
        found.setApprovedBy("admin@corp.com");
        identityMappingRepository.save(found);

        var approved = identityMappingRepository.findById(found.getId()).orElseThrow();
        assertTrue(approved.isApproved());
        assertEquals("admin@corp.com", approved.getApprovedBy());
    }

    @Test
    void sessionQueryByPrincipal() {
        Session s1 = new Session("agent1", "slack", "user@test.com", SessionScope.MAIN);
        Session s2 = new Session("agent2", "teams", "user@test.com", SessionScope.MAIN);
        Session s3 = new Session("agent1", "slack", "other@test.com", SessionScope.MAIN);

        sessionRepository.save(s1);
        sessionRepository.save(s2);
        sessionRepository.save(s3);

        var sessions = sessionRepository.findByPrincipalAndStatus(
                "user@test.com", SessionStatus.ACTIVE);
        assertEquals(2, sessions.size());
    }
}
