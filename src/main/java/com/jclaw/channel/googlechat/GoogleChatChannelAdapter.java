package com.jclaw.channel.googlechat;

import com.jclaw.channel.ChannelAdapter;
import com.jclaw.channel.InboundMessage;
import com.jclaw.channel.OutboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.Map;

@Component
@ConditionalOnProperty(name = "vcap.services.jclaw-secrets.credentials.google-chat-credentials", matchIfMissing = false)
public class GoogleChatChannelAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(GoogleChatChannelAdapter.class);

    private final Sinks.Many<InboundMessage> messageSink =
            Sinks.many().multicast().onBackpressureBuffer();

    @Override
    public String channelType() { return "google-chat"; }

    @Override
    public Flux<InboundMessage> receiveMessages() {
        return messageSink.asFlux();
    }

    @Override
    public Mono<Void> sendMessage(OutboundMessage msg) {
        return Mono.fromRunnable(() -> {
            // Google Chat API HTTP push response
            log.debug("Sending Google Chat message to {}", msg.conversationId());
        });
    }

    @Override
    public Mono<Void> sendTypingIndicator(String conversationId) {
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
