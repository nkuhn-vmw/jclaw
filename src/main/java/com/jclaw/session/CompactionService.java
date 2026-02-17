package com.jclaw.session;

import com.jclaw.agent.AgentContext;
import com.jclaw.agent.AgentRuntime;
import com.jclaw.config.JclawProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CompactionService {

    private static final Logger log = LoggerFactory.getLogger(CompactionService.class);

    private final SessionManager sessionManager;
    private final SessionRepository sessionRepository;
    private final SessionMessageRepository messageRepository;
    private final JclawProperties properties;

    public CompactionService(SessionManager sessionManager,
                            SessionRepository sessionRepository,
                            SessionMessageRepository messageRepository,
                            JclawProperties properties) {
        this.sessionManager = sessionManager;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${jclaw.session.compaction-check-interval-ms:300000}")
    public void checkAndCompact() {
        int threshold = properties.getSession().getCompactionThresholdTokens();
        List<Session> activeSessions = sessionRepository.findByStatus(SessionStatus.ACTIVE);

        for (Session session : activeSessions) {
            int tokenCount = sessionManager.getTokenCount(session.getId());
            if (tokenCount > threshold) {
                log.info("Session {} exceeds compaction threshold ({} > {}), compacting",
                        session.getId(), tokenCount, threshold);
                compactSession(session);
            }
        }
    }

    @Transactional
    public void compactSession(Session session) {
        List<SessionMessage> messages = messageRepository
                .findBySessionIdAndCompactedFalseOrderByCreatedAtAsc(session.getId());

        if (messages.size() <= 2) return; // nothing to compact

        // Keep the most recent messages, compact the older ones
        int keepCount = Math.max(2, messages.size() / 4);
        List<SessionMessage> toCompact = messages.subList(0, messages.size() - keepCount);

        StringBuilder summary = new StringBuilder("Previous conversation summary:\n");
        for (SessionMessage msg : toCompact) {
            summary.append(String.format("[%s] %s\n", msg.getRole(),
                    truncate(msg.getContent(), 200)));
            msg.setCompacted(true);
            messageRepository.save(msg);
        }

        // Insert compaction summary as a system message
        SessionMessage compactionMsg = new SessionMessage(
                session.getId(), MessageRole.SYSTEM, summary.toString());
        compactionMsg.setTokenCount(estimateTokens(summary.toString()));
        messageRepository.save(compactionMsg);

        log.info("Compacted {} messages in session {}", toCompact.size(), session.getId());
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    private int estimateTokens(String text) {
        return text != null ? text.length() / 4 : 0;
    }
}
