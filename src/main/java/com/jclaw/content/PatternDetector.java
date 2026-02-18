package com.jclaw.content;

import com.jclaw.agent.AgentContext;
import com.jclaw.channel.InboundMessage;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

@Component
@Order(2)
public class PatternDetector implements ContentFilter {

    private static final List<Pattern> SUSPICIOUS_PATTERNS = List.of(
            Pattern.compile("(?i)\\bignore\\s+(all\\s+)?previous\\s+instructions\\b"),
            Pattern.compile("(?i)\\byou\\s+are\\s+now\\b"),
            Pattern.compile("(?i)\\bnew\\s+system\\s+prompt\\b"),
            Pattern.compile("(?i)\\boverride\\s+(system|safety)\\b"),
            Pattern.compile("(?i)\\b(jailbreak|DAN|do\\s+anything\\s+now)\\b"),
            Pattern.compile("(?i)<\\s*system\\s*>"),
            Pattern.compile("(?i)\\[INST\\]"),
            Pattern.compile("(?i)\\bact\\s+as\\s+if\\s+you\\s+have\\s+no\\s+restrictions\\b")
    );

    @Override
    public String name() { return "PatternDetector"; }

    @Override
    public FilterResult filter(InboundMessage message, AgentContext context) {
        String content = message.content();
        for (Pattern pattern : SUSPICIOUS_PATTERNS) {
            if (pattern.matcher(content).find()) {
                return FilterResult.reject("Suspicious pattern detected in message");
            }
        }
        return FilterResult.pass();
    }
}
