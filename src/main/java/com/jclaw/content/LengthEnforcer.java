package com.jclaw.content;

import com.jclaw.agent.AgentContext;
import com.jclaw.channel.InboundMessage;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(3)
public class LengthEnforcer implements ContentFilter {

    private static final int DEFAULT_MAX_MESSAGE_LENGTH = 50_000; // 50K characters

    @Override
    public String name() { return "LengthEnforcer"; }

    @Override
    public FilterResult filter(InboundMessage message, AgentContext context, ContentFilterPolicy policy) {
        int maxLength = policy != null ? policy.getMaxMessageLength() : DEFAULT_MAX_MESSAGE_LENGTH;
        if (message.content() != null && message.content().length() > maxLength) {
            return FilterResult.reject(String.format(
                    "Message exceeds maximum length (%d > %d characters)",
                    message.content().length(), maxLength));
        }
        return FilterResult.pass();
    }

    @Override
    public FilterResult filter(InboundMessage message, AgentContext context) {
        return filter(message, context, null);
    }
}
