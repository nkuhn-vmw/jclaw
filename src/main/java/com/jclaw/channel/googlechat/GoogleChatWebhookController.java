package com.jclaw.channel.googlechat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Receives Google Chat HTTP push event payloads.
 */
@RestController
@RequestMapping("/webhooks/google-chat")
@ConditionalOnBean(GoogleChatChannelAdapter.class)
public class GoogleChatWebhookController {

    private static final Logger log = LoggerFactory.getLogger(GoogleChatWebhookController.class);

    private final GoogleChatChannelAdapter googleChatAdapter;

    public GoogleChatWebhookController(GoogleChatChannelAdapter googleChatAdapter) {
        this.googleChatAdapter = googleChatAdapter;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> handleEvent(@RequestBody Map<String, Object> event) {
        String type = (String) event.getOrDefault("type", "");

        if ("ADDED_TO_SPACE".equals(type)) {
            log.info("Bot added to Google Chat space");
            return ResponseEntity.ok(Map.of("text", "Hello! I'm jclaw, your AI assistant."));
        }

        if (!"MESSAGE".equals(type)) {
            return ResponseEntity.ok().build();
        }

        Map<String, Object> messageObj = asMap(event.get("message"));
        Map<String, Object> sender = asMap(event.get("user"));
        Map<String, Object> space = asMap(event.get("space"));

        String userId = sender != null ? (String) sender.get("name") : null;
        String spaceId = space != null ? (String) space.get("name") : null;
        String text = messageObj != null ? (String) messageObj.getOrDefault("text", "") : "";

        // Reject malformed events with missing identity fields
        if (userId == null || userId.isBlank() || spaceId == null || spaceId.isBlank()) {
            log.warn("Google Chat event rejected: missing userId or spaceId");
            return ResponseEntity.badRequest().build();
        }

        // Extract thread name for thread-based replies
        String threadName = null;
        if (messageObj != null) {
            Map<String, Object> thread = asMap(messageObj.get("thread"));
            if (thread != null) {
                threadName = (String) thread.get("name");
            }
        }

        // Detect DM vs group space from Google Chat space type
        String spaceType = space != null ? (String) space.get("type") : null;
        java.util.Map<String, Object> metadata = new java.util.HashMap<>(event);
        if ("DM".equals(spaceType)) {
            metadata.put("isDm", true);
        } else if ("ROOM".equals(spaceType) || "SPACE".equals(spaceType)) {
            metadata.put("isGroup", true);
        }

        log.debug("Google Chat event: type={} user={} space={} thread={}", type, userId, spaceId, threadName);
        googleChatAdapter.processEvent(userId, spaceId, text, threadName, metadata);

        // Return empty body to avoid double-message (async adapter sends the real reply)
        return ResponseEntity.ok().build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object obj) {
        return obj instanceof Map ? (Map<String, Object>) obj : null;
    }
}
