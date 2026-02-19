package com.jclaw.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private static final TypeReference<List<CachedMessage>> CACHED_MSG_LIST_TYPE =
            new TypeReference<>() {};

    private final SessionRepository sessionRepository;
    private final SessionMessageRepository messageRepository;
    private final AuditService auditService;
    private final JclawProperties properties;
    private final JclawMetrics metrics;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public SessionManager(SessionRepository sessionRepository,
                         SessionMessageRepository messageRepository,
                         AuditService auditService,
                         JclawProperties properties,
                         JclawMetrics metrics,
                         ReactiveRedisTemplate<String, String> redisTemplate,
                         ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.auditService = auditService;
        this.properties = properties;
        this.metrics = metrics;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Observed(name = "jclaw.session.resolve", contextualName = "session-resolve")
    @Transactional
    public Session resolveSession(AgentContext context, InboundMessage message) {
        SessionScope scope = resolveScope(message);
        var activeStatuses = List.of(SessionStatus.ACTIVE, SessionStatus.COMPACTED);

        if (scope == SessionScope.GROUP && message.conversationId() != null) {
            return sessionRepository
                    .findByAgentIdAndChannelTypeAndChannelConversationIdAndStatusIn(
                            context.agentId(), message.channelType(),
                            message.conversationId(), activeStatuses)
                    .orElseGet(() -> createSession(context, message, scope));
        }

        // DM scope: per-user-per-channel-per-agent (spec §7.2)
        if (scope == SessionScope.DM) {
            return sessionRepository
                    .findByAgentIdAndPrincipalAndChannelTypeAndScopeAndStatusIn(
                            context.agentId(), context.principal(), message.channelType(),
                            scope, activeStatuses)
                    .orElseGet(() -> createSession(context, message, scope));
        }

        // MAIN/API scope: per-user-per-agent (cross-channel)
        return sessionRepository
                .findByAgentIdAndPrincipalAndScopeAndStatusIn(
                        context.agentId(), context.principal(), scope, activeStatuses)
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
        // Channel-agnostic group detection via metadata flag set by each adapter
        if (message.metadata() != null && Boolean.TRUE.equals(message.metadata().get("isGroup"))) {
            return SessionScope.GROUP;
        }
        // DM scope for direct messages (per-user-per-channel isolation)
        if (message.metadata() != null && Boolean.TRUE.equals(message.metadata().get("isDm"))) {
            return SessionScope.DM;
        }
        try {
            return SessionScope.valueOf(properties.getSession().getDefaultScope());
        } catch (IllegalArgumentException e) {
            log.error("Invalid jclaw.session.default-scope '{}', defaulting to MAIN",
                    properties.getSession().getDefaultScope());
            return SessionScope.MAIN;
        }
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
        String cacheKey = HISTORY_CACHE_PREFIX + sessionId;

        // Try to read cached history from Redis
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey).block();
            if (cached != null && !cached.isEmpty()) {
                List<SessionMessage> fromCache = deserializeHistory(sessionId, cached);
                if (fromCache != null) {
                    log.debug("Session history cache hit for {} ({} messages)", sessionId, fromCache.size());
                    return fromCache;
                }
            }
        } catch (Exception e) {
            log.debug("Redis unavailable for session history cache, using DB directly");
        }

        List<SessionMessage> history = messageRepository
                .findBySessionIdAndCompactedFalseOrderByCreatedAtAsc(sessionId);

        // Cache serialized history in Redis with TTL
        try {
            String serialized = serializeHistory(history);
            redisTemplate.opsForValue()
                    .set(cacheKey, serialized, HISTORY_CACHE_TTL)
                    .subscribe();
        } catch (Exception e) {
            // Redis unavailable, continue without cache
        }

        return history;
    }

    public int getTokenCount(UUID sessionId) {
        return messageRepository.sumTokensBySessionId(sessionId);
    }

    public Session getSession(UUID sessionId) {
        return sessionRepository.findById(sessionId).orElse(null);
    }

    public List<Session> getActiveSessions(String principal) {
        return sessionRepository.findByPrincipalAndStatus(principal, SessionStatus.ACTIVE);
    }

    public List<Session> getActiveSessionsForAgent(String agentId) {
        return sessionRepository.findByAgentIdAndStatus(agentId, SessionStatus.ACTIVE);
    }

    public List<Session> getAllActiveSessions() {
        return sessionRepository.findByStatus(SessionStatus.ACTIVE);
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

    private String serializeHistory(List<SessionMessage> messages) {
        try {
            List<CachedMessage> cached = messages.stream()
                    .map(m -> new CachedMessage(m.getId().toString(), m.getRole().name(),
                            m.getContent(), m.getTokenCount(),
                            m.getToolCalls(), m.getToolResults(), m.getMetadata(),
                            m.getCreatedAt() != null ? m.getCreatedAt().toString() : null))
                    .toList();
            return objectMapper.writeValueAsString(cached);
        } catch (Exception e) {
            log.debug("Failed to serialize session history for cache", e);
            return null;
        }
    }

    private List<SessionMessage> deserializeHistory(UUID sessionId, String json) {
        try {
            List<CachedMessage> cached = objectMapper.readValue(json, CACHED_MSG_LIST_TYPE);
            return cached.stream().map(c -> {
                SessionMessage msg = new SessionMessage(sessionId,
                        MessageRole.valueOf(c.role()), c.content());
                msg.setTokenCount(c.tokenCount());
                msg.setToolCalls(c.toolCalls());
                msg.setToolResults(c.toolResults());
                msg.setMetadata(c.metadata());
                return msg;
            }).toList();
        } catch (Exception e) {
            log.debug("Failed to deserialize session history from cache", e);
            return null;
        }
    }

    private record CachedMessage(String id, String role, String content,
                                  Integer tokenCount, String toolCalls,
                                  String toolResults, String metadata,
                                  String createdAt) {}
}
