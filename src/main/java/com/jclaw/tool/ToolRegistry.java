package com.jclaw.tool;

import com.jclaw.agent.AgentConfig;
import com.jclaw.agent.AgentConfigService;
import com.jclaw.agent.AgentContext;
import com.jclaw.audit.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final ApplicationContext applicationContext;

    public ToolRegistry(ToolPolicy toolPolicy,
                       AgentConfigService agentConfigService,
                       AuditService auditService,
                       ApplicationContext applicationContext) {
        this.toolPolicy = toolPolicy;
        this.agentConfigService = agentConfigService;
        this.auditService = auditService;
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    public void discoverTools() {
        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(JclawTool.class);
        for (Object bean : beans.values()) {
            // Use AnnotationUtils to handle CGLIB proxies correctly
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
        // Wrap callback with audit-logging proxy
        ToolCallback auditedCallback = new AuditedToolCallback(callback, name, auditService);
        tools.put(name, new ToolEntry(name, description, riskLevel, requiresApproval, auditedCallback));
        log.info("Registered tool: {} (risk={}, requiresApproval={})", name, riskLevel, requiresApproval);
    }

    public List<ToolCallback> resolveTools(AgentContext context) {
        AgentConfig config = agentConfigService.getAgentConfig(context.agentId());
        return tools.values().stream()
                .filter(entry -> toolPolicy.isToolAllowed(
                        entry.name(), entry.riskLevel(), entry.requiresApproval(), config))
                .map(ToolEntry::callback)
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
     * Wraps a ToolCallback to emit audit events on every invocation.
     */
    private static class AuditedToolCallback implements ToolCallback {
        private final ToolCallback delegate;
        private final String toolName;
        private final AuditService auditService;

        AuditedToolCallback(ToolCallback delegate, String toolName, AuditService auditService) {
            this.delegate = delegate;
            this.toolName = toolName;
            this.auditService = auditService;
        }

        @Override
        public String call(String toolInput) {
            try {
                String result = delegate.call(toolInput);
                auditService.logToolCall(null, null, null, toolName, "SUCCESS",
                        "{\"input_length\":" + (toolInput != null ? toolInput.length() : 0) + "}");
                return result;
            } catch (Exception e) {
                auditService.logToolCall(null, null, null, toolName, "FAILURE",
                        "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
                throw e;
            }
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }
    }
}
