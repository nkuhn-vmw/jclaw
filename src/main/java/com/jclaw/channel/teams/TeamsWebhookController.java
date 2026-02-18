package com.jclaw.channel.teams;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Receives Teams Bot Framework webhook activity payloads.
 */
@RestController
@RequestMapping("/webhooks/teams")
@ConditionalOnBean(TeamsChannelAdapter.class)
public class TeamsWebhookController {

    private static final Logger log = LoggerFactory.getLogger(TeamsWebhookController.class);

    private final TeamsChannelAdapter teamsAdapter;

    public TeamsWebhookController(TeamsChannelAdapter teamsAdapter) {
        this.teamsAdapter = teamsAdapter;
    }

    @PostMapping
    public ResponseEntity<Void> handleActivity(@RequestBody Map<String, Object> activity) {
        String type = (String) activity.getOrDefault("type", "");
        if (!"message".equals(type)) {
            return ResponseEntity.ok().build();
        }

        Map<String, Object> from = asMap(activity.get("from"));
        Map<String, Object> conversation = asMap(activity.get("conversation"));

        String userId = from != null ? (String) from.get("id") : null;
        String conversationId = conversation != null ? (String) conversation.get("id") : null;
        String text = (String) activity.getOrDefault("text", "");

        // Reject malformed activities with missing identity fields
        if (userId == null || userId.isBlank() || conversationId == null || conversationId.isBlank()) {
            log.warn("Teams activity rejected: missing userId or conversationId");
            return ResponseEntity.badRequest().build();
        }

        // Detect DM vs group conversation from Teams conversation type
        String conversationType = conversation != null ? (String) conversation.get("conversationType") : null;
        java.util.Map<String, Object> metadata = new java.util.HashMap<>(activity);
        if ("personal".equals(conversationType)) {
            metadata.put("isDm", true);
        } else if ("channel".equals(conversationType) || "groupChat".equals(conversationType)) {
            metadata.put("isGroup", true);
        }

        log.debug("Teams activity: type={} user={} conversation={}", type, userId, conversationId);
        teamsAdapter.processActivity(userId, conversationId, text, metadata);

        return ResponseEntity.ok().build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object obj) {
        return obj instanceof Map ? (Map<String, Object>) obj : null;
    }
}
