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

    public InboundMessage filterInbound(InboundMessage message, AgentContext context) {
        // Resolve per-agent content filter policy
        ContentFilterPolicy policy = resolvePolicy(context.agentId());

        InboundMessage current = message;
        for (ContentFilter filter : filters) {
            // EgressGuard only applies to outbound content, skip on inbound
            if ("EgressGuard".equals(filter.name())) continue;

            // Skip filters disabled by per-agent policy
            if (!isFilterEnabled(filter, policy)) {
                log.debug("Filter {} disabled by policy for agent={}", filter.name(), context.agentId());
                continue;
            }

            ContentFilter.FilterResult result = filter.filter(current, context, policy);
            if (!result.passed()) {
                metrics.recordContentFilterTriggered(filter.name(), "REJECTED");
                auditService.logContentFilter(filter.name(), "REJECTED",
                        context.principal(), message.channelType(), "FILTERED");
                log.warn("Content filter {} rejected message from principal={}: {}",
                        filter.name(), context.principal(), result.reason());
                throw new ContentFilterException(filter.name(), result.reason());
            }

            // Propagate sanitized content from filters like InputSanitizer
            if (result.sanitizedContent() != null && !result.sanitizedContent().equals(current.content())) {
                current = new InboundMessage(current.channelType(), current.channelUserId(),
                        current.conversationId(), current.threadId(), result.sanitizedContent(),
                        current.metadata(), current.receivedAt());
            }
        }
        return current;
    }

    public ContentFilterPolicy resolvePolicy(String agentId) {
        AgentConfig config = agentConfigService.getAgentConfig(agentId);
        if (config != null && config.getContentFilterPolicy() != null) {
            return config.getContentFilterPolicy();
        }
        return new ContentFilterPolicy(); // default policy
    }

    /**
     * Filters outbound content (tool outputs, LLM responses) for data exfiltration.
     * Only runs the EgressGuard filter on outbound content.
     */
    public void filterOutbound(String content, AgentContext context) {
        filterOutbound(content, context, resolvePolicy(context.agentId()));
    }

    public void filterOutbound(String content, AgentContext context, ContentFilterPolicy policy) {
        if (!policy.isEnableEgressGuard()) return;

        // Create a synthetic inbound message to reuse the EgressGuard filter
        InboundMessage synthetic = new InboundMessage(
                context.channelType(), "system", null, null, content,
                java.util.Map.of(), java.time.Instant.now());

        for (ContentFilter filter : filters) {
            if ("EgressGuard".equals(filter.name())) {
                ContentFilter.FilterResult result = filter.filter(synthetic, context, policy);
                if (!result.passed()) {
                    metrics.recordContentFilterTriggered("EgressGuard", "BLOCKED_OUTBOUND");
                    auditService.logContentFilter("EgressGuard", "BLOCKED_OUTBOUND",
                            context.principal(), context.channelType(), "FILTERED");
                    log.warn("Egress guard blocked outbound content for agent={}: {}",
                            context.agentId(), result.reason());
                    throw new ContentFilterException("EgressGuard", result.reason());
                }
            }
        }
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
