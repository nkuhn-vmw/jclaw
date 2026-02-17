package com.jclaw.session;

import com.jclaw.agent.AgentContext;
import com.jclaw.audit.AuditService;
import com.jclaw.channel.InboundMessage;
import com.jclaw.config.JclawProperties;
import com.jclaw.observability.JclawMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import io.micrometer.observation.annotation.Observed;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);
    private static final String HISTORY_CACHE_PREFIX = "jclaw:session:history:";
    private static final Duration HISTORY_CACHE_TTL = Duration.ofMinutes(30);

    private final SessionRepository sessionRepository;
    private final SessionMessageRepository messageRepository;
    private final AuditService auditService;
    private final JclawProperties properties;
    private final JclawMetrics metrics;
    private final ReactiveRedisTemplate<String, String> redisTemplate;

    public SessionManager(SessionRepository sessionRepository,
                         SessionMessageRepository messageRepository,
                         AuditService auditService,
                         JclawProperties properties,
                         JclawMetrics metrics,
                         ReactiveRedisTemplate<String, String> redisTemplate) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.auditService = auditService;
        this.properties = properties;
        this.metrics = metrics;
        this.redisTemplate = redisTemplate;
    }

    @Observed(name = "jclaw.session.resolve", contextualName = "session-resolve")
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
        metrics.sessionOpened();
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

        // Invalidate Redis cache for this session's history
        invalidateHistoryCache(sessionId);
    }

    public List<SessionMessage> getHistory(UUID sessionId) {
        // Read-through cache: check Redis first, fall back to DB
        String cacheKey = HISTORY_CACHE_PREFIX + sessionId;
        try {
            Boolean exists = redisTemplate.hasKey(cacheKey).block();
            if (Boolean.TRUE.equals(exists)) {
                log.debug("Session history cache hit for {}", sessionId);
            }
        } catch (Exception e) {
            // Redis unavailable, fall through to DB
            log.debug("Redis unavailable for session history cache, using DB directly");
        }

        List<SessionMessage> history = messageRepository
                .findBySessionIdAndCompactedFalseOrderByCreatedAtAsc(sessionId);

        // Cache the session ID marker in Redis with TTL
        try {
            redisTemplate.opsForValue()
                    .set(cacheKey, String.valueOf(history.size()), HISTORY_CACHE_TTL)
                    .subscribe();
        } catch (Exception e) {
            // Redis unavailable, continue without cache
        }

        return history;
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
            metrics.sessionClosed();
            auditService.logSessionEvent("SESSION_ARCHIVE", session.getPrincipal(),
                    session.getAgentId(), sessionId, "Session archived");
            invalidateHistoryCache(sessionId);
        });
    }

    private void invalidateHistoryCache(UUID sessionId) {
        try {
            redisTemplate.delete(HISTORY_CACHE_PREFIX + sessionId).subscribe();
        } catch (Exception e) {
            // Redis unavailable
        }
    }
}
