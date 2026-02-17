package com.jclaw.tool.builtin;

import com.jclaw.agent.AgentConfig;
import com.jclaw.agent.AgentConfigService;
import com.jclaw.tool.JclawTool;
import com.jclaw.tool.RiskLevel;
import com.jclaw.tool.validation.EgressAllowlistValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
@JclawTool(
        name = "http_fetch",
        description = "Fetch content from a URL. Subject to egress allowlist restrictions.",
        riskLevel = RiskLevel.MEDIUM,
        requiresApproval = false
)
public class HttpFetchTool implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(HttpFetchTool.class);

    private final WebClient webClient;
    private final EgressAllowlistValidator egressValidator;
    private final AgentConfigService agentConfigService;

    public HttpFetchTool(EgressAllowlistValidator egressValidator,
                        AgentConfigService agentConfigService) {
        this.webClient = WebClient.builder()
                .codecs(config -> config.defaultCodecs().maxInMemorySize(1024 * 1024))
                .build();
        this.egressValidator = egressValidator;
        this.agentConfigService = agentConfigService;
    }

    @Override
    public String call(String toolInput) {
        try {
            String url = extractField(toolInput, "url");
            if (url == null) return "{\"error\": \"No URL provided\"}";

            String agentId = extractField(toolInput, "agentId");
            AgentConfig config = agentId != null ? agentConfigService.getAgentConfig(agentId) : null;

            // Enforce egress allowlist
            if (!egressValidator.isAllowed(url, config)) {
                log.warn("Egress blocked: url={} agentId={}", url, agentId);
                return "{\"error\": \"URL not in egress allowlist\"}";
            }

            String body = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (body != null && body.length() > 10000) {
                body = body.substring(0, 10000) + "\n... (truncated)";
            }

            return body != null ? body : "{\"error\": \"Empty response\"}";
        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("http_fetch")
                .description("Fetch content from a URL (egress allowlist enforced)")
                .inputSchema("""
                    {"type":"object","properties":{
                      "url":{"type":"string","description":"The URL to fetch content from"},
                      "agentId":{"type":"string","description":"Agent ID for egress policy lookup"}
                    },"required":["url"]}""")
                .build();
    }

    private String extractField(String toolInput, String field) {
        if (toolInput == null) return null;
        int idx = toolInput.indexOf("\"" + field + "\"");
        if (idx < 0) {
            if (field.equals("url")) return toolInput.trim().replaceAll("^\"|\"$", "");
            return null;
        }
        int start = toolInput.indexOf("\"", idx + field.length() + 2);
        if (start < 0) return null;
        int end = toolInput.indexOf("\"", start + 1);
        if (end < 0) return null;
        return toolInput.substring(start + 1, end);
    }
}
