package com.jclaw.content;

import com.jclaw.agent.AgentContext;
import com.jclaw.channel.InboundMessage;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

@Component
@Order(4)
public class InstructionDetector implements ContentFilter {

    private static final List<Pattern> INSTRUCTION_PATTERNS = List.of(
            Pattern.compile("(?i)\\bforget\\s+(everything|what|all)\\b"),
            Pattern.compile("(?i)\\breset\\s+your\\s+(context|memory|instructions)\\b"),
            Pattern.compile("(?i)\\bpretend\\s+(you|that|to)\\b"),
            Pattern.compile("(?i)\\byour\\s+new\\s+(role|instructions|task)\\b"),
            Pattern.compile("(?i)\\bfrom\\s+now\\s+on\\b.*\\b(you\\s+are|act\\s+as)\\b"),
            Pattern.compile("(?i)\\bdisregard\\b.*\\b(above|previous|prior|earlier)\\b")
    );

    @Override
    public String name() { return "InstructionDetector"; }

    @Override
    public FilterResult filter(InboundMessage message, AgentContext context) {
        String content = message.content();
        for (Pattern pattern : INSTRUCTION_PATTERNS) {
            if (pattern.matcher(content).find()) {
                return FilterResult.reject("Instruction manipulation attempt detected");
            }
        }
        return FilterResult.pass();
    }
}
