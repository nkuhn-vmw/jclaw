package com.jclaw.tool;

import com.jclaw.agent.AgentConfig;
import com.jclaw.agent.AgentConfigService;
import com.jclaw.agent.AgentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.ApplicationContext;
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
    private final ApplicationContext applicationContext;

    public ToolRegistry(ToolPolicy toolPolicy,
                       AgentConfigService agentConfigService,
                       ApplicationContext applicationContext) {
        this.toolPolicy = toolPolicy;
        this.agentConfigService = agentConfigService;
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    public void discoverTools() {
        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(JclawTool.class);
        for (Object bean : beans.values()) {
            JclawTool annotation = bean.getClass().getAnnotation(JclawTool.class);
            if (annotation != null && bean instanceof ToolCallback callback) {
                registerTool(annotation.name(), annotation.description(),
                        annotation.riskLevel(), callback);
            }
        }
        log.info("Tool registry initialized with {} tools", tools.size());
    }

    public void registerTool(String name, String description,
                            RiskLevel riskLevel, ToolCallback callback) {
        tools.put(name, new ToolEntry(name, description, riskLevel, callback));
        log.info("Registered tool: {} (risk={})", name, riskLevel);
    }

    public List<ToolCallback> resolveTools(AgentContext context) {
        AgentConfig config = agentConfigService.getAgentConfig(context.agentId());
        return tools.values().stream()
                .filter(entry -> toolPolicy.isToolAllowed(
                        entry.name(), entry.riskLevel(), config))
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
            ToolCallback callback
    ) {}
}
