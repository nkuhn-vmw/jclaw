package com.jclaw.agent;

public record AgentContext(
        String agentId,
        String principal,
        String channelType
) {}
