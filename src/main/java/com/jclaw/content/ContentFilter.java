package com.jclaw.content;

import com.jclaw.agent.AgentContext;
import com.jclaw.channel.InboundMessage;

public interface ContentFilter {

    String name();

    /**
     * Filter with per-agent policy. Default delegates to legacy method.
     */
    default FilterResult filter(InboundMessage message, AgentContext context, ContentFilterPolicy policy) {
        return filter(message, context);
    }

    FilterResult filter(InboundMessage message, AgentContext context);

    record FilterResult(boolean passed, String reason) {
        public static FilterResult pass() { return new FilterResult(true, null); }
        public static FilterResult reject(String reason) { return new FilterResult(false, reason); }
    }
}
