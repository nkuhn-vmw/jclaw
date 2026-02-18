package com.jclaw.tool;

import com.jclaw.agent.AgentConfig;
import com.jclaw.agent.AgentConfigService;
import com.jclaw.agent.AgentContext;
import com.jclaw.audit.AuditService;
import com.jclaw.observability.JclawMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, ToolEntry> tools = new ConcurrentHashMap<>();
    private final ToolPolicy toolPolicy;
    private final AgentConfigService agentConfigService;
    private final AuditService auditService;
    private final JclawMetrics metrics;
    private final ApplicationContext applicationContext;

    public ToolRegistry(ToolPolicy toolPolicy,
                       AgentConfigService agentConfigService,
                       AuditService auditService,
                       JclawMetrics metrics,
                       ApplicationContext applicationContext) {
        this.toolPolicy = toolPolicy;
        this.agentConfigService = agentConfigService;
        this.auditService = auditService;
        this.metrics = metrics;
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    public void discoverTools() {
        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(JclawTool.class);
        for (Object bean : beans.values()) {
            JclawTool annotation = AnnotationUtils.findAnnotation(bean.getClass(), JclawTool.class);
            if (annotation != null && bean instanceof ToolCallback callback) {
                registerTool(annotation.name(), annotation.description(),
                        annotation.riskLevel(), annotation.requiresApproval(), callback);
            }
        }
        log.info("Tool registry initialized with {} tools", tools.size());
    }

    public void registerTool(String name, String description,
                            RiskLevel riskLevel, boolean requiresApproval,
                            ToolCallback callback) {
        tools.put(name, new ToolEntry(name, description, riskLevel, requiresApproval, callback));
        log.info("Registered tool: {} (risk={}, requiresApproval={})", name, riskLevel, requiresApproval);
    }

    public List<ToolCallback> resolveTools(AgentContext context) {
        AgentConfig config = agentConfigService.getAgentConfig(context.agentId());
        return tools.values().stream()
                .filter(entry -> toolPolicy.isToolAllowed(
                        entry.name(), entry.riskLevel(), entry.requiresApproval(), config))
                .map(entry -> new AuditedToolCallback(
                        entry.callback(), entry.name(), auditService, metrics, context))
                .collect(Collectors.toList());
    }

    public Optional<ToolEntry> getTool(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public Collection<ToolEntry> getAllTools() {
        return Collections.unmodifiableCollection(tools.values());
    }

    public record ToolEntry(
            String name,
            String description,
            RiskLevel riskLevel,
            boolean requiresApproval,
            ToolCallback callback
    ) {}

    /**
     * Wraps a ToolCallback to emit audit events and metrics on every invocation.
     * Created per-request with the current AgentContext for proper attribution.
     */
    private static class AuditedToolCallback implements ToolCallback {
        private final ToolCallback delegate;
        private final String toolName;
        private final AuditService auditService;
        private final JclawMetrics metrics;
        private final AgentContext context;

        AuditedToolCallback(ToolCallback delegate, String toolName,
                           AuditService auditService, JclawMetrics metrics,
                           AgentContext context) {
            this.delegate = delegate;
            this.toolName = toolName;
            this.auditService = auditService;
            this.metrics = metrics;
            this.context = context;
        }

        @Override
        public String call(String toolInput) {
            // Propagate MDC context to whatever thread Spring AI uses for tool execution
            MDC.put("agentId", context.agentId());
            MDC.put("principal", context.principal());
            MDC.put("channelType", context.channelType());
            try {
                String result = delegate.call(toolInput);
                auditService.logToolCall(context.principal(), context.agentId(),
                        null, toolName, "SUCCESS",
                        "{\"input_length\":" + (toolInput != null ? toolInput.length() : 0) + "}");
                metrics.recordToolInvocation(toolName, context.agentId(), "success");
                return result;
            } catch (Exception e) {
                auditService.logToolCall(context.principal(), context.agentId(),
                        null, toolName, "FAILURE",
                        "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
                metrics.recordToolInvocation(toolName, context.agentId(), "failure");
                throw e;
            }
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }
    }
}
