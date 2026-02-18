package com.jclaw.session;

import com.jclaw.config.JclawProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class CompactionService {

    private static final Logger log = LoggerFactory.getLogger(CompactionService.class);

    private final SessionManager sessionManager;
    private final SessionRepository sessionRepository;
    private final SessionMessageRepository messageRepository;
    private final JclawProperties properties;
    private final ChatModel chatModel;

    public CompactionService(SessionManager sessionManager,
                            SessionRepository sessionRepository,
                            SessionMessageRepository messageRepository,
                            JclawProperties properties,
                            ChatModel chatModel) {
        this.sessionManager = sessionManager;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.properties = properties;
        this.chatModel = chatModel;
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

        if (messages.size() <= 2) return;

        // Keep the most recent messages, compact the older ones
        int keepCount = Math.max(2, messages.size() / 4);
        List<SessionMessage> toCompact = messages.subList(0, messages.size() - keepCount);

        // Build conversation text for LLM summarization
        String conversationText = toCompact.stream()
                .map(msg -> String.format("[%s]: %s", msg.getRole(), msg.getContent()))
                .collect(Collectors.joining("\n"));

        // Generate LLM summary
        String summary = generateLlmSummary(conversationText);

        // Mark old messages as compacted
        for (SessionMessage msg : toCompact) {
            msg.setCompacted(true);
            messageRepository.save(msg);
        }

        // Insert compaction summary as a system message
        SessionMessage compactionMsg = new SessionMessage(
                session.getId(), MessageRole.SYSTEM, summary);
        compactionMsg.setTokenCount(estimateTokens(summary));
        messageRepository.save(compactionMsg);

        // Mark session as COMPACTED — resolveSession() now queries both ACTIVE and COMPACTED
        session.setStatus(SessionStatus.COMPACTED);
        sessionRepository.save(session);

        log.info("Compacted {} messages in session {} using LLM summary",
                toCompact.size(), session.getId());
    }

    private String generateLlmSummary(String conversationText) {
        try {
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage("You are a conversation summarizer. Generate a concise, " +
                            "comprehensive summary of the following conversation. Preserve key facts, " +
                            "decisions, action items, and important context. The summary will replace " +
                            "the original messages in the conversation history."),
                    new UserMessage("Summarize this conversation:\n\n" + conversationText)
            ));

            ChatResponse response = chatModel.call(prompt);
            String summary = response.getResult().getOutput().getText();
            return "Previous conversation summary:\n" + summary;
        } catch (Exception e) {
            log.warn("LLM summarization failed, falling back to text truncation", e);
            return fallbackSummary(conversationText);
        }
    }

    private String fallbackSummary(String conversationText) {
        // Fallback: truncate if LLM is unavailable
        StringBuilder summary = new StringBuilder("Previous conversation summary:\n");
        String[] lines = conversationText.split("\n");
        for (String line : lines) {
            summary.append(truncate(line, 200)).append("\n");
        }
        return summary.toString();
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    private int estimateTokens(String text) {
        return text != null ? text.length() / 4 : 0;
    }
}
