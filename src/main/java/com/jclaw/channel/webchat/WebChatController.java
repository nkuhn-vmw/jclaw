package com.jclaw.channel.webchat;

import com.jclaw.audit.AuditService;
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
    private final AuditService auditService;

    // Track conversationId -> principal ownership to prevent cross-user SSE eavesdropping
    // Bounded to prevent unbounded memory growth; in multi-instance use Redis instead
    private static final int MAX_CONVERSATION_OWNERS = 10_000;
    private final Map<String, String> conversationOwners = new ConcurrentHashMap<>();

    public WebChatController(WebChatChannelAdapter webChatAdapter, AuditService auditService) {
        this.webChatAdapter = webChatAdapter;
        this.auditService = auditService;
    }

    @PostMapping("/send")
    public Map<String, String> sendMessage(@RequestBody Map<String, String> body,
                                           Authentication auth) {
        String text = body.get("message");
        String clientConversationId = body.get("conversationId");

        if (text == null || text.isBlank()) {
            return Map.of("error", "message is required");
        }

        String userId = auth.getName();

        // Evict stale entries if map exceeds bound to prevent memory leak
        if (conversationOwners.size() >= MAX_CONVERSATION_OWNERS) {
            log.warn("Conversation owners cache full ({} entries), evicting oldest entries",
                    conversationOwners.size());
            var iterator = conversationOwners.entrySet().iterator();
            int toRemove = MAX_CONVERSATION_OWNERS / 10;
            while (iterator.hasNext() && toRemove-- > 0) {
                iterator.next();
                iterator.remove();
            }
        }

        // Determine conversationId: accept client-supplied only if already owned by this user;
        // otherwise always generate server-side to prevent hijacking/guessing
        String conversationId;
        if (clientConversationId != null && userId.equals(conversationOwners.get(clientConversationId))) {
            conversationId = clientConversationId;
        } else {
            conversationId = UUID.randomUUID().toString();
            conversationOwners.put(conversationId, userId);
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
            auditService.logAuth(auth.getName(), "SSE_STREAM:" + conversationId,
                    "DENIED", null);
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
