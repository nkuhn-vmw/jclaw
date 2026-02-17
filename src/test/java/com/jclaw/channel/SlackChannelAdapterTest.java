package com.jclaw.channel;

import com.jclaw.channel.restapi.RestApiChannelAdapter;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.*;

class SlackChannelAdapterTest {

    @Test
    void restApiAdapterReturnsCorrectType() {
        RestApiChannelAdapter adapter = new RestApiChannelAdapter();
        assertEquals("rest-api", adapter.channelType());
        assertFalse(adapter.supportsThreading());
        assertFalse(adapter.supportsReactions());
    }

    @Test
    void restApiAdapterPublishesMessages() {
        RestApiChannelAdapter adapter = new RestApiChannelAdapter();

        InboundMessage message = new InboundMessage("rest-api", "user1", "conv1", "Hello");

        StepVerifier.create(adapter.receiveMessages().take(1))
                .then(() -> adapter.publishMessage(message))
                .expectNext(message)
                .verifyComplete();
    }

    @Test
    void inboundMessageRecordFields() {
        InboundMessage msg = new InboundMessage("slack", "U123", "C456", "Hello world");
        assertEquals("slack", msg.channelType());
        assertEquals("U123", msg.channelUserId());
        assertEquals("C456", msg.conversationId());
        assertEquals("Hello world", msg.content());
        assertNull(msg.threadId());
    }

    @Test
    void outboundMessageRecordFields() {
        OutboundMessage msg = new OutboundMessage("slack", "C456", "Response text");
        assertEquals("slack", msg.channelType());
        assertEquals("C456", msg.conversationId());
        assertEquals("Response text", msg.content());
        assertNull(msg.threadId());
    }
}
