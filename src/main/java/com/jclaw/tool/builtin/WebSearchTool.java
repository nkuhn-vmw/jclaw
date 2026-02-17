package com.jclaw.tool.builtin;

import com.jclaw.tool.JclawTool;
import com.jclaw.tool.RiskLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
@JclawTool(
        name = "web_search",
        description = "Search the web for current information. Returns relevant search results.",
        riskLevel = RiskLevel.LOW,
        requiresApproval = false
)
public class WebSearchTool implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);

    private final WebClient webClient;

    public WebSearchTool() {
        this.webClient = WebClient.builder().build();
    }

    @Override
    public String call(String toolInput) {
        String query = extractField(toolInput, "query");
        if (query == null || query.isBlank()) {
            return "{\"error\": \"query is required\"}";
        }

        // In production, integrate with SerpAPI, Brave Search, or similar.
        // For now, return a structured placeholder indicating the query was received.
        log.info("Web search requested: query={}", query);
        return String.format(
            "{\"query\":\"%s\",\"results\":[],\"message\":\"Web search provider not configured. " +
            "Set vcap.services.jclaw-secrets.credentials.search-api-key to enable.\"}",
            query.replace("\"", "'"));
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("web_search")
                .description("Search the web for current information")
                .inputSchema("""
                    {"type":"object","properties":{
                      "query":{"type":"string","description":"The search query"},
                      "maxResults":{"type":"integer","description":"Maximum results to return","default":5}
                    },"required":["query"]}""")
                .build();
    }

    private String extractField(String json, String field) {
        if (json == null) return null;
        int idx = json.indexOf("\"" + field + "\"");
        if (idx < 0) return json.trim().replaceAll("^\"|\"$", "");
        int start = json.indexOf("\"", idx + field.length() + 2);
        if (start < 0) return null;
        int end = json.indexOf("\"", start + 1);
        if (end < 0) return null;
        return json.substring(start + 1, end);
    }
}
