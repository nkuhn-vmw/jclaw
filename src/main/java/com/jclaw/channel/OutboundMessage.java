package com.jclaw.channel;

import java.util.Map;

public record OutboundMessage(
        String channelType,
        String conversationId,
        String threadId,
        String content,
        Map<String, Object> metadata
) {
    public OutboundMessage(String channelType, String conversationId, String content) {
        this(channelType, conversationId, null, content, Map.of());
    }
}
