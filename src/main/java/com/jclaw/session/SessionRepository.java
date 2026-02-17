package com.jclaw.session;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SessionRepository extends JpaRepository<Session, UUID> {

    Optional<Session> findByAgentIdAndPrincipalAndScopeAndStatus(
            String agentId, String principal, SessionScope scope, SessionStatus status);

    Optional<Session> findByAgentIdAndChannelTypeAndChannelConversationIdAndStatus(
            String agentId, String channelType, String conversationId, SessionStatus status);

    List<Session> findByPrincipalAndStatus(String principal, SessionStatus status);

    List<Session> findByAgentIdAndStatus(String agentId, SessionStatus status);

    List<Session> findByStatus(SessionStatus status);

    List<Session> findByStatusAndLastActiveAtBefore(SessionStatus status, Instant cutoff);
}
