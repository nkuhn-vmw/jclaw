package com.jclaw.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class GenAiHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(GenAiHealthIndicator.class);
    private static final long CACHE_DURATION_MS = 60_000; // cache result for 1 minute

    private final ChatModel chatModel;
    private final AtomicReference<CachedHealth> cachedHealth = new AtomicReference<>();

    public GenAiHealthIndicator(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public Health health() {
        if (chatModel == null) {
            return Health.down().withDetail("reason", "No ChatModel configured").build();
        }

        CachedHealth cached = cachedHealth.get();
        if (cached != null && (System.currentTimeMillis() - cached.timestamp) < CACHE_DURATION_MS) {
            return cached.health;
        }

        try {
            // Lightweight connectivity check: send minimal prompt with 1 max token
            Prompt healthCheckPrompt = new Prompt("hi",
                    OpenAiChatOptions.builder().maxTokens(1).build());
            chatModel.call(healthCheckPrompt);
            Health h = Health.up()
                    .withDetail("model", chatModel.getClass().getSimpleName())
                    .build();
            cachedHealth.set(new CachedHealth(h, System.currentTimeMillis()));
            return h;
        } catch (Exception e) {
            log.warn("GenAI health check failed: {}", e.getMessage());
            Health h = Health.down()
                    .withDetail("model", chatModel.getClass().getSimpleName())
                    .withDetail("error", e.getMessage())
                    .build();
            cachedHealth.set(new CachedHealth(h, System.currentTimeMillis()));
            return h;
        }
    }

    private record CachedHealth(Health health, long timestamp) {}
}
