package com.jclaw.agent;

import java.util.Map;

public record AgentResponse(
        String content,
        String finishReason,
        Map<String, Object> metadata
) {
    public AgentResponse(String content) {
        this(content, "stop", Map.of());
    }
}
