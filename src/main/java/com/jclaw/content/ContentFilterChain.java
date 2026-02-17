package com.jclaw.content;

import com.jclaw.agent.AgentContext;
import com.jclaw.audit.AuditService;
import com.jclaw.channel.InboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ContentFilterChain {

    private static final Logger log = LoggerFactory.getLogger(ContentFilterChain.class);

    private final List<ContentFilter> filters;
    private final AuditService auditService;

    public ContentFilterChain(List<ContentFilter> filters, AuditService auditService) {
        this.filters = filters;
        this.auditService = auditService;
    }

    public void filterInbound(InboundMessage message, AgentContext context) {
        for (ContentFilter filter : filters) {
            ContentFilter.FilterResult result = filter.filter(message, context);
            if (!result.passed()) {
                auditService.logContentFilter(filter.name(), "REJECTED",
                        context.principal(), message.channelType(), "FILTERED");
                log.warn("Content filter {} rejected message from principal={}: {}",
                        filter.name(), context.principal(), result.reason());
                throw new ContentFilterException(filter.name(), result.reason());
            }
        }
    }

    public static class ContentFilterException extends RuntimeException {
        private final String filterName;

        public ContentFilterException(String filterName, String reason) {
            super("Content rejected by " + filterName + ": " + reason);
            this.filterName = filterName;
        }

        public String getFilterName() { return filterName; }
    }
}
