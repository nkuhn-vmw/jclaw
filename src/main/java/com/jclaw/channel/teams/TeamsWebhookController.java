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

        String userId = from != null ? (String) from.get("id") : "unknown";
        String conversationId = conversation != null ? (String) conversation.get("id") : "unknown";
        String text = (String) activity.getOrDefault("text", "");

        log.debug("Teams activity: type={} user={} conversation={}", type, userId, conversationId);
        teamsAdapter.processActivity(userId, conversationId, text, activity);

        return ResponseEntity.ok().build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object obj) {
        return obj instanceof Map ? (Map<String, Object>) obj : null;
    }
}
