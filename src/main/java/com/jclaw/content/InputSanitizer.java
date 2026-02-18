package com.jclaw.content;

import com.jclaw.agent.AgentContext;
import com.jclaw.channel.InboundMessage;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.text.Normalizer;

@Component
@Order(1)
public class InputSanitizer implements ContentFilter {

    @Override
    public String name() { return "InputSanitizer"; }

    @Override
    public FilterResult filter(InboundMessage message, AgentContext context) {
        String content = message.content();
        if (content == null || content.isBlank()) {
            return FilterResult.reject("Empty message content");
        }

        // Strip control characters (except common whitespace) instead of rejecting
        StringBuilder cleaned = new StringBuilder(content.length());
        for (char c : content.toCharArray()) {
            if (!Character.isISOControl(c) || c == '\n' || c == '\r' || c == '\t') {
                cleaned.append(c);
            }
        }
        String sanitized = cleaned.toString();

        if (sanitized.isBlank()) {
            return FilterResult.reject("Message contains only control characters");
        }

        // Check for unicode normalization issues (homoglyph attacks)
        String normalized = Normalizer.normalize(sanitized, Normalizer.Form.NFC);
        if (normalized.length() > sanitized.length() * 2) {
            return FilterResult.reject("Suspicious unicode content detected");
        }

        return FilterResult.pass(normalized);
    }
}
