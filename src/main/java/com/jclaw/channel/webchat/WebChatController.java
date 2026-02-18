package com.jclaw.channel.webchat;

import com.jclaw.channel.OutboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REST + SSE controller for the web chat channel.
 */
@RestController
@RequestMapping("/api/webchat")
public class WebChatController {

    private static final Logger log = LoggerFactory.getLogger(WebChatController.class);

    private final WebChatChannelAdapter webChatAdapter;

    // Track conversationId -> principal ownership to prevent cross-user SSE eavesdropping
    private final Map<String, String> conversationOwners = new ConcurrentHashMap<>();

    public WebChatController(WebChatChannelAdapter webChatAdapter) {
        this.webChatAdapter = webChatAdapter;
    }

    @PostMapping("/send")
    public Map<String, String> sendMessage(@RequestBody Map<String, String> body,
                                           Authentication auth) {
        String text = body.get("message");
        // Always generate conversationId server-side to prevent hijacking
        String conversationId = body.getOrDefault("conversationId",
                UUID.randomUUID().toString());

        if (text == null || text.isBlank()) {
            return Map.of("error", "message is required");
        }

        String userId = auth.getName();

        // Register ownership: first user to use a conversationId owns it
        conversationOwners.putIfAbsent(conversationId, userId);
        if (!userId.equals(conversationOwners.get(conversationId))) {
            return Map.of("error", "conversationId belongs to another user");
        }

        webChatAdapter.publishMessage(userId, conversationId, text);
        log.debug("WebChat message sent: user={} conversation={}", userId, conversationId);

        return Map.of("conversationId", conversationId, "status", "sent");
    }

    @GetMapping(value = "/stream/{conversationId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamMessages(
            @PathVariable String conversationId,
            Authentication auth) {
        // Verify the requesting user owns this conversationId
        String owner = conversationOwners.get(conversationId);
        if (owner != null && !owner.equals(auth.getName())) {
            log.warn("SSE stream denied: user={} attempted to access conversation={} owned by={}",
                    auth.getName(), conversationId, owner);
            return Flux.empty();
        }

        log.debug("SSE stream opened: conversation={} user={}",
                conversationId, auth.getName());

        return webChatAdapter.subscribeClient(conversationId)
                .map(msg -> {
                    String eventType = msg.metadata() != null
                            && "typing".equals(msg.metadata().get("type"))
                            ? "typing" : "message";
                    return ServerSentEvent.<String>builder()
                            .event(eventType)
                            .data(msg.content())
                            .build();
                });
    }
}
