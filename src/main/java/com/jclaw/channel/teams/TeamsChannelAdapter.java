package com.jclaw.channel.teams;

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
@ConditionalOnProperty(name = "vcap.services.jclaw-secrets.credentials.teams-app-password", matchIfMissing = false)
public class TeamsChannelAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(TeamsChannelAdapter.class);

    private final Sinks.Many<InboundMessage> messageSink =
            Sinks.many().multicast().onBackpressureBuffer();

    @Override
    public String channelType() { return "teams"; }

    @Override
    public Flux<InboundMessage> receiveMessages() {
        return messageSink.asFlux();
    }

    @Override
    public Mono<Void> sendMessage(OutboundMessage msg) {
        return Mono.fromRunnable(() -> {
            // Teams Bot Framework send via connector client
            log.debug("Sending Teams message to {}", msg.conversationId());
        });
    }

    @Override
    public Mono<Void> sendTypingIndicator(String conversationId) {
        return Mono.fromRunnable(() -> {
            log.debug("Sending typing indicator to Teams conversation {}", conversationId);
        });
    }

    @Override
    public boolean supportsThreading() { return true; }

    @Override
    public boolean supportsReactions() { return true; }

    /**
     * Called by the Teams webhook controller to process incoming activities.
     */
    public void processActivity(String userId, String conversationId,
                               String text, Map<String, Object> metadata) {
        InboundMessage msg = new InboundMessage("teams", userId, conversationId,
                null, text, metadata, java.time.Instant.now());
        messageSink.tryEmitNext(msg);
    }
}
