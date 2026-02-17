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

        // Check for control characters (except common whitespace)
        for (char c : content.toCharArray()) {
            if (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t') {
                return FilterResult.reject("Message contains control characters");
            }
        }

        // Check for unicode normalization issues (homoglyph attacks)
        String normalized = Normalizer.normalize(content, Normalizer.Form.NFC);
        if (normalized.length() > content.length() * 2) {
            return FilterResult.reject("Suspicious unicode content detected");
        }

        return FilterResult.pass();
    }
}
