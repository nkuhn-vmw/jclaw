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
    void restApiAdapterSendMessageIsNoop() {
        RestApiChannelAdapter adapter = new RestApiChannelAdapter();

        OutboundMessage message = new OutboundMessage("rest-api", "conv1", "Hello");

        // REST API adapter's sendMessage is a no-op (responses go through controller)
        StepVerifier.create(adapter.sendMessage(message))
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
