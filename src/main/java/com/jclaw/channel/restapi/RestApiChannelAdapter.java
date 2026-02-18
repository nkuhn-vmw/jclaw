package com.jclaw.channel.restapi;

import com.jclaw.channel.ChannelAdapter;
import com.jclaw.channel.InboundMessage;
import com.jclaw.channel.OutboundMessage;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * REST API channel adapter. Messages are handled directly by ChatApiController
 * rather than through the ChannelRouter pipeline, so receiveMessages() returns
 * an empty flux and sendMessage() is a no-op.
 */
@Component
public class RestApiChannelAdapter implements ChannelAdapter {

    @Override
    public String channelType() { return "rest-api"; }

    @Override
    public Flux<InboundMessage> receiveMessages() {
        return Flux.never();
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
}
