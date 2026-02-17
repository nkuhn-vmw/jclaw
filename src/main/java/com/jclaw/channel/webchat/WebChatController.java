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

/**
 * REST + SSE controller for the web chat channel.
 */
@RestController
@RequestMapping("/api/webchat")
public class WebChatController {

    private static final Logger log = LoggerFactory.getLogger(WebChatController.class);

    private final WebChatChannelAdapter webChatAdapter;

    public WebChatController(WebChatChannelAdapter webChatAdapter) {
        this.webChatAdapter = webChatAdapter;
    }

    @PostMapping("/send")
    public Map<String, String> sendMessage(@RequestBody Map<String, String> body,
                                           Authentication auth) {
        String text = body.get("message");
        String conversationId = body.getOrDefault("conversationId",
                UUID.randomUUID().toString());

        if (text == null || text.isBlank()) {
            return Map.of("error", "message is required");
        }

        String userId = auth.getName();
        webChatAdapter.publishMessage(userId, conversationId, text);
        log.debug("WebChat message sent: user={} conversation={}", userId, conversationId);

        return Map.of("conversationId", conversationId, "status", "sent");
    }

    @GetMapping(value = "/stream/{conversationId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamMessages(
            @PathVariable String conversationId,
            Authentication auth) {
        log.debug("SSE stream opened: conversation={} user={}",
                conversationId, auth.getName());

        return webChatAdapter.subscribeClient(conversationId)
                .map(msg -> ServerSentEvent.<String>builder()
                        .event("message")
                        .data(msg.content())
                        .build());
    }
}
