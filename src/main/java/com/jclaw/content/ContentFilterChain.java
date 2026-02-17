package com.jclaw.content;

import com.jclaw.agent.AgentConfig;
import com.jclaw.agent.AgentConfigService;
import com.jclaw.agent.AgentContext;
import com.jclaw.audit.AuditService;
import com.jclaw.channel.InboundMessage;
import com.jclaw.observability.JclawMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ContentFilterChain {

    private static final Logger log = LoggerFactory.getLogger(ContentFilterChain.class);

    private final List<ContentFilter> filters;
    private final AuditService auditService;
    private final JclawMetrics metrics;
    private final AgentConfigService agentConfigService;

    public ContentFilterChain(List<ContentFilter> filters, AuditService auditService,
                             JclawMetrics metrics, AgentConfigService agentConfigService) {
        this.filters = filters;
        this.auditService = auditService;
        this.metrics = metrics;
        this.agentConfigService = agentConfigService;
    }

    public void filterInbound(InboundMessage message, AgentContext context) {
        // Resolve per-agent content filter policy
        ContentFilterPolicy policy = resolvePolicy(context.agentId());

        for (ContentFilter filter : filters) {
            // Skip filters disabled by per-agent policy
            if (!isFilterEnabled(filter, policy)) {
                log.debug("Filter {} disabled by policy for agent={}", filter.name(), context.agentId());
                continue;
            }

            ContentFilter.FilterResult result = filter.filter(message, context, policy);
            if (!result.passed()) {
                metrics.recordContentFilterTriggered(filter.name(), "REJECTED");
                auditService.logContentFilter(filter.name(), "REJECTED",
                        context.principal(), message.channelType(), "FILTERED");
                log.warn("Content filter {} rejected message from principal={}: {}",
                        filter.name(), context.principal(), result.reason());
                throw new ContentFilterException(filter.name(), result.reason());
            }
        }
    }

    private ContentFilterPolicy resolvePolicy(String agentId) {
        AgentConfig config = agentConfigService.getAgentConfig(agentId);
        if (config != null && config.getContentFilterPolicy() != null) {
            return config.getContentFilterPolicy();
        }
        return new ContentFilterPolicy(); // default policy
    }

    private boolean isFilterEnabled(ContentFilter filter, ContentFilterPolicy policy) {
        return switch (filter.name()) {
            case "PatternDetector" -> policy.isEnablePatternDetection();
            case "InstructionDetector" -> policy.isEnableInstructionDetection();
            case "EgressGuard" -> policy.isEnableEgressGuard();
            default -> true; // InputSanitizer, LengthEnforcer always run
        };
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
