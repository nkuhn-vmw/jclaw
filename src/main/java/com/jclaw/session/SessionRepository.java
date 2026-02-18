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

    Optional<Session> findByAgentIdAndPrincipalAndScopeAndStatusIn(
            String agentId, String principal, SessionScope scope, java.util.Collection<SessionStatus> statuses);

    // DM scope: per-user-per-channel-per-agent isolation (spec §7.2)
    Optional<Session> findByAgentIdAndPrincipalAndChannelTypeAndScopeAndStatusIn(
            String agentId, String principal, String channelType, SessionScope scope,
            java.util.Collection<SessionStatus> statuses);

    Optional<Session> findByAgentIdAndChannelTypeAndChannelConversationIdAndStatus(
            String agentId, String channelType, String conversationId, SessionStatus status);

    Optional<Session> findByAgentIdAndChannelTypeAndChannelConversationIdAndStatusIn(
            String agentId, String channelType, String conversationId, java.util.Collection<SessionStatus> statuses);

    List<Session> findByPrincipalAndStatus(String principal, SessionStatus status);

    List<Session> findByAgentIdAndStatus(String agentId, SessionStatus status);

    List<Session> findByStatus(SessionStatus status);

    List<Session> findByStatusAndLastActiveAtBefore(SessionStatus status, Instant cutoff);

    List<Session> findByStatusInAndLastActiveAtBefore(
            java.util.Collection<SessionStatus> statuses, Instant cutoff);
}
