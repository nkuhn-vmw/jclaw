package com.jclaw.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class GenAiHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(GenAiHealthIndicator.class);

    private final ChatModel chatModel;

    public GenAiHealthIndicator(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public Health health() {
        try {
            // Verify the model bean is available and configured
            if (chatModel == null) {
                return Health.down().withDetail("reason", "No ChatModel configured").build();
            }
            return Health.up()
                    .withDetail("model", chatModel.getClass().getSimpleName())
                    .build();
        } catch (Exception e) {
            log.warn("GenAI health check failed", e);
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
