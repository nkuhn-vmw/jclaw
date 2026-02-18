package com.jclaw.channel;

import java.time.Instant;
import java.util.Map;

public record OutboundMessage(
        String channelType,
        String conversationId,
        String threadId,
        String content,
        Map<String, Object> metadata,
        Instant sentAt
) {
    public OutboundMessage(String channelType, String conversationId, String threadId,
                           String content, Map<String, Object> metadata) {
        this(channelType, conversationId, threadId, content, metadata, Instant.now());
    }

    public OutboundMessage(String channelType, String conversationId, String content) {
        this(channelType, conversationId, null, content, Map.of(), Instant.now());
    }
}
