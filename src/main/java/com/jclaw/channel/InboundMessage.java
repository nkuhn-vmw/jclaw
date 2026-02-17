package com.jclaw.channel;

import java.time.Instant;
import java.util.Map;

public record InboundMessage(
        String channelType,
        String channelUserId,
        String conversationId,
        String threadId,
        String content,
        Map<String, Object> metadata,
        Instant receivedAt
) {
    public InboundMessage(String channelType, String channelUserId,
                         String conversationId, String content) {
        this(channelType, channelUserId, conversationId, null, content, Map.of(), Instant.now());
    }
}
