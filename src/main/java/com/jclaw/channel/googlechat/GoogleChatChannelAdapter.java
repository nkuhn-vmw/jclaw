package com.jclaw.channel.googlechat;

import com.jclaw.channel.ChannelAdapter;
import com.jclaw.channel.InboundMessage;
import com.jclaw.channel.OutboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.Map;

@Component
@ConditionalOnProperty(name = "vcap.services.jclaw-secrets.credentials.google-chat-credentials", matchIfMissing = false)
public class GoogleChatChannelAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(GoogleChatChannelAdapter.class);
    private static final String GOOGLE_CHAT_API = "https://chat.googleapis.com/v1";

    private final Sinks.Many<InboundMessage> messageSink =
            Sinks.many().multicast().onBackpressureBuffer();
    private final WebClient webClient;

    public GoogleChatChannelAdapter() {
        this.webClient = WebClient.builder()
                .baseUrl(GOOGLE_CHAT_API)
                .build();
    }

    @Override
    public String channelType() { return "google-chat"; }

    @Override
    public Flux<InboundMessage> receiveMessages() {
        return messageSink.asFlux();
    }

    @Override
    public Mono<Void> sendMessage(OutboundMessage msg) {
        // Use Google Chat API to send messages to the space
        String spaceId = msg.conversationId();

        Map<String, Object> message = Map.of(
                "text", msg.content()
        );

        // Derive the thread key from metadata if available
        String threadKey = msg.metadata() != null
                ? (String) msg.metadata().get("threadKey") : null;

        WebClient.RequestBodySpec requestSpec = webClient.post()
                .uri(uriBuilder -> {
                    uriBuilder.path("/{spaceId}/messages");
                    if (threadKey != null) {
                        uriBuilder.queryParam("messageReplyOption", "REPLY_MESSAGE_FALLBACK_TO_NEW_THREAD");
                    }
                    return uriBuilder.build(spaceId);
                });

        if (threadKey != null) {
            message = Map.of(
                    "text", msg.content(),
                    "thread", Map.of("name", threadKey)
            );
        }

        return requestSpec
                .bodyValue(message)
                .retrieve()
                .bodyToMono(Void.class)
                .doOnSuccess(v -> log.debug("Google Chat message sent to {}", spaceId))
                .doOnError(e -> log.error("Failed to send Google Chat message to {}: {}",
                        spaceId, e.getMessage()))
                .onErrorResume(e -> Mono.empty());
    }

    @Override
    public Mono<Void> sendTypingIndicator(String conversationId) {
        // Google Chat API does not support typing indicators
        return Mono.empty();
    }

    @Override
    public boolean supportsThreading() { return true; }

    @Override
    public boolean supportsReactions() { return false; }

    /**
     * Called by the Google Chat webhook controller to process incoming messages.
     */
    public void processEvent(String userId, String spaceId, String text,
                            Map<String, Object> metadata) {
        InboundMessage msg = new InboundMessage("google-chat", userId, spaceId,
                null, text, metadata, java.time.Instant.now());
        messageSink.tryEmitNext(msg);
    }
}
