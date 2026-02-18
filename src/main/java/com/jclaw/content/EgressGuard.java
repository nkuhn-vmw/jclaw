package com.jclaw.content;

import com.jclaw.agent.AgentContext;
import com.jclaw.channel.InboundMessage;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Detects potential data exfiltration attempts in tool outputs.
 * Also screens inbound messages for suspicious URL patterns.
 */
@Component
@Order(5)
public class EgressGuard implements ContentFilter {

    private static final List<Pattern> EXFIL_PATTERNS = List.of(
            Pattern.compile("(?i)\\bsend\\s+(this|the|all|my)\\b.*\\bto\\b.*\\b(email|webhook|url|http)\\b"),
            Pattern.compile("(?i)\\bexfiltrate\\b"),
            Pattern.compile("(?i)\\bupload\\s+(this|the|all|my)\\b.*\\bto\\b")
    );

    @Override
    public String name() { return "EgressGuard"; }

    @Override
    public FilterResult filter(InboundMessage message, AgentContext context) {
        return filter(message, context, null);
    }

    @Override
    public FilterResult filter(InboundMessage message, AgentContext context, ContentFilterPolicy policy) {
        String content = message.content();

        // Check URL patterns against egress allowlist if this is outbound content
        // The allowlist is passed via policy's parent AgentConfig, but since ContentFilterChain
        // resolves the policy, we check patterns here on all content
        for (Pattern pattern : EXFIL_PATTERNS) {
            if (pattern.matcher(content).find()) {
                return FilterResult.reject("Potential data exfiltration attempt detected");
            }
        }
        return FilterResult.pass();
    }
}
