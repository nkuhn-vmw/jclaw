package com.jclaw.channel.webchat;

import com.jclaw.channel.ChannelAdapter;
import com.jclaw.channel.InboundMessage;
import com.jclaw.channel.OutboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WebChatChannelAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(WebChatChannelAdapter.class);

    private final Sinks.Many<InboundMessage> messageSink =
            Sinks.many().multicast().onBackpressureBuffer();
    private final Map<String, Sinks.Many<OutboundMessage>> clientSinks =
            new ConcurrentHashMap<>();

    @Override
    public String channelType() { return "webchat"; }

    @Override
    public Flux<InboundMessage> receiveMessages() {
        return messageSink.asFlux();
    }

    @Override
    public Mono<Void> sendMessage(OutboundMessage msg) {
        return Mono.fromRunnable(() -> {
            Sinks.Many<OutboundMessage> clientSink = clientSinks.get(msg.conversationId());
            if (clientSink != null) {
                clientSink.tryEmitNext(msg);
            }
        });
    }

    @Override
    public Mono<Void> sendTypingIndicator(String conversationId) {
        return Mono.fromRunnable(() -> {
            Sinks.Many<OutboundMessage> clientSink = clientSinks.get(conversationId);
            if (clientSink != null) {
                // Send a typing event that the SSE client can distinguish from real messages
                clientSink.tryEmitNext(new OutboundMessage("webchat", conversationId,
                        null, "", Map.of("type", "typing")));
            }
        });
    }

    @Override
    public boolean supportsThreading() { return false; }

    @Override
    public boolean supportsReactions() { return false; }

    public void publishMessage(String userId, String conversationId, String text) {
        InboundMessage msg = new InboundMessage("webchat", userId, conversationId,
                null, text, Map.of(), java.time.Instant.now());
        messageSink.tryEmitNext(msg);
    }

    public Flux<OutboundMessage> subscribeClient(String conversationId) {
        Sinks.Many<OutboundMessage> sink = clientSinks.computeIfAbsent(conversationId,
                k -> Sinks.many().multicast().onBackpressureBuffer());
        return sink.asFlux()
                .doOnCancel(() -> clientSinks.remove(conversationId))
                .doOnError(e -> clientSinks.remove(conversationId))
                .doOnTerminate(() -> clientSinks.remove(conversationId));
    }
}
