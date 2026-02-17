package com.jclaw.channel.restapi;

import com.jclaw.channel.ChannelAdapter;
import com.jclaw.channel.InboundMessage;
import com.jclaw.channel.OutboundMessage;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

@Component
public class RestApiChannelAdapter implements ChannelAdapter {

    private final Sinks.Many<InboundMessage> messageSink =
            Sinks.many().multicast().onBackpressureBuffer();

    @Override
    public String channelType() { return "rest-api"; }

    @Override
    public Flux<InboundMessage> receiveMessages() {
        return messageSink.asFlux();
    }

    @Override
    public Mono<Void> sendMessage(OutboundMessage msg) {
        // REST API responses are returned synchronously via the controller
        return Mono.empty();
    }

    @Override
    public Mono<Void> sendTypingIndicator(String conversationId) {
        return Mono.empty();
    }

    @Override
    public boolean supportsThreading() { return false; }

    @Override
    public boolean supportsReactions() { return false; }

    public void publishMessage(InboundMessage message) {
        messageSink.tryEmitNext(message);
    }
}
