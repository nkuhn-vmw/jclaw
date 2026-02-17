package com.jclaw.channel;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ChannelAdapter {

    String channelType();

    Flux<InboundMessage> receiveMessages();

    Mono<Void> sendMessage(OutboundMessage msg);

    Mono<Void> sendTypingIndicator(String conversationId);

    boolean supportsThreading();

    boolean supportsReactions();
}
