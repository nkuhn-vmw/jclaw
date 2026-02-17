package com.jclaw.session;

import com.jclaw.agent.AgentContext;
import com.jclaw.audit.AuditService;
import com.jclaw.channel.InboundMessage;
import com.jclaw.config.JclawProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final SessionRepository sessionRepository;
    private final SessionMessageRepository messageRepository;
    private final AuditService auditService;
    private final JclawProperties properties;

    public SessionManager(SessionRepository sessionRepository,
                         SessionMessageRepository messageRepository,
                         AuditService auditService,
                         JclawProperties properties) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.auditService = auditService;
        this.properties = properties;
    }

    @Transactional
    public Session resolveSession(AgentContext context, InboundMessage message) {
        SessionScope scope = resolveScope(message);

        if (scope == SessionScope.GROUP && message.conversationId() != null) {
            return sessionRepository
                    .findByAgentIdAndChannelTypeAndChannelConversationIdAndStatus(
                            context.agentId(), message.channelType(),
                            message.conversationId(), SessionStatus.ACTIVE)
                    .orElseGet(() -> createSession(context, message, scope));
        }

        return sessionRepository
                .findByAgentIdAndPrincipalAndScopeAndStatus(
                        context.agentId(), context.principal(), scope, SessionStatus.ACTIVE)
                .orElseGet(() -> createSession(context, message, scope));
    }

    private Session createSession(AgentContext context, InboundMessage message, SessionScope scope) {
        Session session = new Session(context.agentId(), message.channelType(),
                context.principal(), scope);
        session.setChannelConversationId(message.conversationId());
        Session saved = sessionRepository.save(session);
        auditService.logSessionEvent("SESSION_CREATE", context.principal(),
                context.agentId(), saved.getId(), "Session created");
        log.info("New session created: id={} agent={} principal={} scope={}",
                saved.getId(), context.agentId(), context.principal(), scope);
        return saved;
    }

    private SessionScope resolveScope(InboundMessage message) {
        if ("rest-api".equals(message.channelType())) return SessionScope.API;
        if (message.conversationId() != null && message.conversationId().startsWith("G")) {
            return SessionScope.GROUP;
        }
        return SessionScope.valueOf(properties.getSession().getDefaultScope());
    }

    @Transactional
    public void addMessage(UUID sessionId, MessageRole role, String content, Integer tokenCount) {
        SessionMessage msg = new SessionMessage(sessionId, role, content);
        msg.setTokenCount(tokenCount);
        messageRepository.save(msg);

        Session session = sessionRepository.findById(sessionId).orElseThrow();
        session.incrementMessageCount();
        if (tokenCount != null) session.addTokens(tokenCount);
        session.touch();
        sessionRepository.save(session);
    }

    public List<SessionMessage> getHistory(UUID sessionId) {
        return messageRepository.findBySessionIdAndCompactedFalseOrderByCreatedAtAsc(sessionId);
    }

    public int getTokenCount(UUID sessionId) {
        return messageRepository.sumTokensBySessionId(sessionId);
    }

    public List<Session> getActiveSessions(String principal) {
        return sessionRepository.findByPrincipalAndStatus(principal, SessionStatus.ACTIVE);
    }

    public List<Session> getActiveSessionsForAgent(String agentId) {
        return sessionRepository.findByAgentIdAndStatus(agentId, SessionStatus.ACTIVE);
    }

    @Transactional
    public void archiveSession(UUID sessionId) {
        sessionRepository.findById(sessionId).ifPresent(session -> {
            session.setStatus(SessionStatus.ARCHIVED);
            sessionRepository.save(session);
            auditService.logSessionEvent("SESSION_ARCHIVE", session.getPrincipal(),
                    session.getAgentId(), sessionId, "Session archived");
        });
    }
}
